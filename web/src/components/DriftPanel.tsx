import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { DeploymentDrift, StrategyDrift } from '../api/types';
import { DRIFT_COLOR, canOverride, driftRows } from '../lib/drift';

/** Live-vs-backtest drift per deployment (PRD 25): the comparison table, the status, what triggered it and the actions taken. */
export function DriftPanel({ strategyId }: { strategyId: string }) {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['drift', strategyId], queryFn: () => request<StrategyDrift>(`/strategies/${strategyId}/drift`), refetchInterval: 60_000 });
  async function override(d: DeploymentDrift) {
    const reason = prompt(`Override drift ${d.state?.status} on this ${d.report.mode} deployment. Reason:`);
    if (!reason) return;
    await request(`/deployments/${d.report.deploymentId}/drift/override`, { method: 'POST', body: { reason } }).catch((e) => alert(e.message));
    qc.invalidateQueries({ queryKey: ['drift', strategyId] });
    qc.invalidateQueries({ queryKey: ['deployments'] });
  }
  if (!data) return null;
  return (
    <div data-testid="drift-panel">
      <h3>Live vs backtest drift</h3>
      {!data.enabled && <p>Drift monitor disabled.</p>}
      {data.deployments.length === 0 && <p>No deployments.</p>}
      {data.deployments.map((d) => (
        <div key={d.report.deploymentId} style={{ marginBottom: 12 }}>
          <div>
            v{d.report.version} · {d.report.mode} · {d.report.enabled ? 'enabled' : 'paused'}
            {d.report.sizeMultiplier < 1 && <> · size ×{d.report.sizeMultiplier.toFixed(2)}</>}
            {' · '}<b data-testid="drift-status" style={{ color: DRIFT_COLOR[d.report.status] }}>{d.report.status}</b>
            {d.state?.overrideStatus && <> · overridden ({d.state.overrideStatus}) by {d.state.overrideBy}: {d.state.overrideReason}</>}
            {canOverride(d) && <button style={{ marginLeft: 8 }} onClick={() => override(d)}>Override…</button>}
          </div>
          <table>
            <thead><tr><th></th><th>Backtest{d.report.backtest?.split === 'OUT_OF_SAMPLE' ? ' (OOS)' : ''}</th><th>Trailing {d.report.window.maxTrades} / {d.report.window.sessions} sessions</th></tr></thead>
            <tbody>{driftRows(d).map((row) => <tr key={row.metric}><td>{row.metric}</td><td>{row.backtest}</td><td>{row.live}</td></tr>)}</tbody>
          </table>
          {d.report.triggered.length > 0 && <ul>{d.report.triggered.map((t) => <li key={t} style={{ color: DRIFT_COLOR[d.report.status] }}>{t}</li>)}</ul>}
          <details><summary>Evidence and history</summary>
            <ul>{d.report.evidence.map((e) => <li key={e}>{e}</li>)}</ul>
            <ul>{d.history.map((h) => <li key={h.id}>{new Date(h.at).toLocaleString()} {h.status}{h.actions.length ? ` — ${h.actions.join(', ')}` : ''}</li>)}</ul>
          </details>
        </div>
      ))}
    </div>
  );
}
