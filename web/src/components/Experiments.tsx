import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Experiment, StrategyVersion } from '../api/types';
import { VERDICT_TONE, basis, canPromote } from '../lib/experiments';
import { Badge, Button, EmptyState } from '../ui';

/** Experiments on a version (plan M4.7): ranked variants, verdicts, overfitting warnings, promote to a new DRAFT version. */
export function Experiments({ versionId }: { versionId: string }) {
  const qc = useQueryClient();
  const { data: experiments } = useQuery({ queryKey: ['experiments', versionId], queryFn: () => request<Experiment[]>(`/experiments?versionId=${versionId}`),
    refetchInterval: 5000 });
  const [open, setOpen] = useState<string | null>(null);
  const [message, setMessage] = useState('');

  async function promote(e: Experiment, variantId: string) {
    try {
      const v = await request<StrategyVersion>(`/experiments/${e.id}/variants/${variantId}/promote`, { method: 'POST' });
      setMessage(`Promoted as v${v.version} (${v.status})`);
      qc.invalidateQueries({ queryKey: ['experiments', versionId] });
      qc.invalidateQueries({ queryKey: ['versions'] });
    } catch (err) { setMessage((err as Error).message); }
  }

  if (!experiments?.length) return <EmptyState title="No experiments yet">Ask Hejje AI to propose variants, or POST /experiments.</EmptyState>;
  return (
    <div data-testid="experiments" className="stack">
      {message && <p className="message">{message}</p>}
      {experiments.map((e) => (
        <div key={e.id} className="stack-sm">
          <Button onClick={() => setOpen(open === e.id ? null : e.id)} aria-expanded={open === e.id}>
            {e.status} · {e.goal ?? 'experiment'} · {e.variants.length} variants · {new Date(e.createdAt).toLocaleString()}
          </Button>
          {open === e.id && (
            <div className="stack-sm">
              {e.notes.map((n) => <p key={n} className="tone-warning text-sm">⚠ {n}</p>)}
              <div className="table-scroll">
                <table>
                  <thead><tr><th className="num">#</th><th>Variant</th><th>Verdict</th><th className="num">Score</th><th>Basis</th><th className="num">Exp R</th><th className="num">PF</th><th className="num">DD R</th><th className="num">Trades</th><th className="num">Params</th><th>Warnings</th><th /></tr></thead>
                  <tbody>
                    {e.variants.map((v) => {
                      const b = v.metrics ? basis(v.metrics) : null;
                      return (
                        <tr key={v.id} title={v.description ?? ''}>
                          <td className="num">{v.rank ?? '—'}</td><td>{v.name}</td>
                          <td><Badge tone={VERDICT_TONE[v.verdict ?? ''] ?? 'neutral'}>{v.verdict ?? v.status}</Badge></td>
                          <td className="num">{v.score ?? '—'}</td><td>{b?.name ?? '—'}</td><td className="num">{b ? b.split.expectancyR.toFixed(2) : '—'}</td>
                          <td className="num">{b?.split.profitFactor?.toFixed(2) ?? '—'}</td><td className="num">{v.metrics ? v.metrics.overall.maxDrawdownR.toFixed(1) : '—'}</td>
                          <td className="num">{v.metrics?.overall.trades ?? '—'}</td><td className="num">{v.parameterCount}</td>
                          <td className="why">{v.error ?? v.warnings.map((w) => w.split(':')[0]).join(', ')}</td>
                          <td>{canPromote(v) ? <Button size="sm" onClick={() => promote(e, v.id)}>Promote to version</Button> : v.promotedVersionId ? 'promoted' : ''}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
