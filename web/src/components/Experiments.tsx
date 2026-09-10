import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Experiment, StrategyVersion } from '../api/types';
import { VERDICT_COLOR, basis, canPromote } from '../lib/experiments';

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

  if (!experiments?.length) return <p style={{ color: '#9e9e9e' }}>No experiments yet — ask Hejje AI to propose variants, or POST /experiments.</p>;
  return (
    <div data-testid="experiments">
      {message && <p>{message}</p>}
      {experiments.map((e) => (
        <div key={e.id} style={{ marginBottom: 8 }}>
          <button onClick={() => setOpen(open === e.id ? null : e.id)}>
            {e.status} · {e.goal ?? 'experiment'} · {e.variants.length} variants · {new Date(e.createdAt).toLocaleString()}
          </button>
          {open === e.id && (
            <div>
              {e.notes.map((n) => <p key={n} style={{ fontSize: 12, color: '#ef6c00' }}>⚠ {n}</p>)}
              <table style={{ fontSize: 12, borderCollapse: 'collapse' }}>
                <thead><tr><th>#</th><th align="left">Variant</th><th>Verdict</th><th>Score</th><th>Basis</th><th>Exp R</th><th>PF</th><th>DD R</th><th>Trades</th><th>Params</th><th align="left">Warnings</th><th /></tr></thead>
                <tbody>
                  {e.variants.map((v) => {
                    const b = v.metrics ? basis(v.metrics) : null;
                    return (
                      <tr key={v.id} title={v.description ?? ''}>
                        <td>{v.rank ?? '—'}</td><td>{v.name}</td>
                        <td style={{ color: VERDICT_COLOR[v.verdict ?? ''] ?? '#9e9e9e', fontWeight: 600 }}>{v.verdict ?? v.status}</td>
                        <td>{v.score ?? '—'}</td><td>{b?.name ?? '—'}</td><td>{b ? b.split.expectancyR.toFixed(2) : '—'}</td>
                        <td>{b?.split.profitFactor?.toFixed(2) ?? '—'}</td><td>{v.metrics ? v.metrics.overall.maxDrawdownR.toFixed(1) : '—'}</td>
                        <td>{v.metrics?.overall.trades ?? '—'}</td><td>{v.parameterCount}</td>
                        <td style={{ maxWidth: 320 }}>{v.error ?? v.warnings.map((w) => w.split(':')[0]).join(', ')}</td>
                        <td>{canPromote(v) ? <button onClick={() => promote(e, v.id)}>Promote to version</button> : v.promotedVersionId ? 'promoted' : ''}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
