import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { DeploymentDrift, StrategyDrift } from '../api/types';
import { DRIFT_TONE, canOverride, driftRows } from '../lib/drift';
import { Badge, Button, Card, Dialog, Field } from '../ui';

/** Live-vs-backtest drift per deployment (PRD 25): the comparison table, the status, what triggered it and the actions taken. */
export function DriftPanel({ strategyId }: { strategyId: string }) {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['drift', strategyId], queryFn: () => request<StrategyDrift>(`/strategies/${strategyId}/drift`), refetchInterval: 60_000 });
  const [overriding, setOverriding] = useState<DeploymentDrift | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');

  async function override() {
    const d = overriding;
    if (!d || !reason.trim()) return;
    try {
      await request(`/deployments/${d.report.deploymentId}/drift/override`, { method: 'POST', body: { reason } });
      setOverriding(null);
      setReason('');
    } catch (e) {
      setError((e as Error).message);
    }
    qc.invalidateQueries({ queryKey: ['drift', strategyId] });
    qc.invalidateQueries({ queryKey: ['deployments'] });
  }
  if (!data) return null;
  return (
    <Card title="Live vs backtest drift" data-testid="drift-panel">
      <div className="stack">
        {!data.enabled && <p>Drift monitor disabled.</p>}
        {data.deployments.length === 0 && <p className="muted">No deployments.</p>}
        {data.deployments.map((d) => (
          <div key={d.report.deploymentId} className="stack-sm">
            <div className="cluster">
              v{d.report.version} · {d.report.mode} · {d.report.enabled ? 'enabled' : 'paused'}
              {d.report.sizeMultiplier < 1 && <> · size ×{d.report.sizeMultiplier.toFixed(2)}</>}
              <Badge tone={DRIFT_TONE[d.report.status]} data-testid="drift-status">{d.report.status}</Badge>
              {d.state?.overrideStatus && <span> overridden ({d.state.overrideStatus}) by {d.state.overrideBy}: {d.state.overrideReason}</span>}
              {canOverride(d) && <Button size="sm" onClick={() => { setOverriding(d); setError(''); }}>Override…</Button>}
            </div>
            <div className="table-scroll">
              <table>
                <thead><tr><th></th><th className="num">Backtest{d.report.backtest?.split === 'OUT_OF_SAMPLE' ? ' (OOS)' : ''}</th><th className="num">Trailing {d.report.window.maxTrades} / {d.report.window.sessions} sessions</th></tr></thead>
                <tbody>{driftRows(d).map((row) => <tr key={row.metric}><td>{row.metric}</td><td className="num">{row.backtest}</td><td className="num">{row.live}</td></tr>)}</tbody>
              </table>
            </div>
            {d.report.triggered.length > 0 && <ul className={`tone-${DRIFT_TONE[d.report.status]}`}>{d.report.triggered.map((t) => <li key={t}>{t}</li>)}</ul>}
            <details><summary>Evidence and history</summary>
              <ul>{d.report.evidence.map((e) => <li key={e}>{e}</li>)}</ul>
              <ul>{d.history.map((h) => <li key={h.id}>{new Date(h.at).toLocaleString()} {h.status}{h.actions.length ? ` — ${h.actions.join(', ')}` : ''}</li>)}</ul>
            </details>
          </div>
        ))}
      </div>
      <Dialog
        open={overriding !== null}
        title="Override drift"
        onClose={() => setOverriding(null)}
        actions={<>
          <Button onClick={() => setOverriding(null)}>Cancel</Button>
          <Button variant="danger" disabled={!reason.trim()} onClick={override}>Override</Button>
        </>}
      >
        {overriding && (
          <div className="stack">
            <p>Override drift {overriding.state?.status} on this {overriding.report.mode} deployment.</p>
            <Field label="Reason" error={error || undefined}><input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          </div>
        )}
      </Dialog>
    </Card>
  );
}
