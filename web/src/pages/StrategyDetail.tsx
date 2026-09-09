import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { request } from '../api/client';
import { Backtest, BacktestTrade, Deployment, RegimeBreakdown, ScoreView, Strategy, StrategyVersion } from '../api/types';
import { formatPaise } from '../lib/sizing';
import { formatR } from '../lib/today';
import { EquityChart } from '../components/EquityChart';

function Rules({ definition }: { definition: any }) {
  if (!definition) return null;
  const rules: string[] = [];
  const entry = definition.entry;
  rules.push(`Entry when ${entry.mode === 'ALL' ? 'all' : 'any'} of: ${entry.conditions.join(' ; ')}`);
  if (definition.exit) rules.push(`Exit when ${definition.exit.mode === 'ALL' ? 'all' : 'any'} of: ${definition.exit.conditions.join(' ; ')}`);
  rules.push(`Stop: ${definition.stop.type}${definition.stop.value != null ? ` ${definition.stop.value}` : ''}`);
  rules.push(`Target: ${definition.target.type}${definition.target.value != null ? ` ${definition.target.value}` : ''}`);
  if (definition.trailingStop) rules.push(`Trailing: ${definition.trailingStop.type} ${definition.trailingStop.value}`);
  rules.push(`Window ${definition.tradeWindow.start}–${definition.tradeWindow.end}, force exit ${definition.forceExitTime}, max ${definition.maxTradesPerDay}/day`);
  return <ul data-testid="rule-list">{rules.map((r) => <li key={r}>{r}</li>)}</ul>;
}

function Metrics({ b }: { b: Backtest }) {
  const m = b.metrics;
  if (!m) return <p>{b.status} {b.error ?? ''}</p>;
  return (
    <table>
      <tbody>
        <tr><td>Trades</td><td>{m.totalTrades}</td><td>Win rate</td><td>{Math.round(m.winRate * 100)}%</td></tr>
        <tr><td>Expectancy</td><td>{formatR(m.expectancyR)}</td><td>Profit factor</td><td>{m.profitFactor?.toFixed(2) ?? '—'}</td></tr>
        <tr><td>Max DD</td><td>-{m.maxDrawdownR.toFixed(1)}R</td><td>Sharpe</td><td>{m.sharpe?.toFixed(2) ?? '—'}</td></tr>
        <tr><td>Gross</td><td>{formatPaise(m.grossPnl.paise)}</td><td>Costs</td><td>{formatPaise(m.totalCosts.paise)}</td></tr>
        <tr><td>Net</td><td>{formatPaise(m.netPnl.paise)}</td><td>Sessions</td><td>{b.sessionsWithData}/{b.sessionsExpected}</td></tr>
      </tbody>
    </table>
  );
}

function Regimes({ r }: { r: RegimeBreakdown }) {
  return (
    <div data-testid="regime-breakdown">
      <h4>By regime ({r.dims.join(' × ')})</h4>
      <p>
        {r.similar
          ? <>Similar regime <b>{r.similar.current}</b>: {r.similar.trades} trades, {formatR(r.similar.expectancyR)} expectancy vs {formatR(r.similar.overallExpectancyR)} overall</>
          : <>Similar regime: {r.note ?? 'n/a'}</>}
      </p>
      <table>
        <thead><tr><th>Regime</th><th>Trades</th><th>Win rate</th><th>Expectancy</th><th>PF</th><th>Net</th></tr></thead>
        <tbody>
          {r.byRegime.map((b) => (
            <tr key={b.key}>
              <td>{b.key}</td><td>{b.trades}</td><td>{Math.round(b.winRate * 100)}%</td><td>{formatR(b.expectancyR)}</td>
              <td>{b.profitFactor?.toFixed(2) ?? '—'}</td><td>{formatPaise(b.netPnl.paise)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function StrategyDetail() {
  const { id } = useParams();
  const qc = useQueryClient();
  const [selected, setSelected] = useState<number | null>(null);
  const [compareA, setCompareA] = useState<number | null>(null);
  const [backtestId, setBacktestId] = useState<string | null>(null);
  const { data: strategy } = useQuery({ queryKey: ['strategy', id], queryFn: () => request<Strategy>(`/strategies/${id}`) });
  const { data: versions } = useQuery({ queryKey: ['versions', id], queryFn: () => request<StrategyVersion[]>(`/strategies/${id}/versions`) });
  const version = versions?.find((v) => v.version === (selected ?? strategy?.latestVersion));
  const { data: score } = useQuery({ queryKey: ['score', id, version?.version], enabled: !!version,
    queryFn: () => request<ScoreView>(`/strategies/${id}/score?version=${version!.version}`) });
  const { data: backtests } = useQuery({ queryKey: ['backtests', version?.id], enabled: !!version,
    queryFn: () => request<Backtest[]>(`/backtests?versionId=${version!.id}`), refetchInterval: 5000 });
  const { data: deployments } = useQuery({ queryKey: ['deployments', version?.id], enabled: !!version,
    queryFn: () => request<Deployment[]>(`/deployments?versionId=${version!.id}`) });
  const shown = backtests?.find((b) => b.id === backtestId) ?? backtests?.[0];
  const { data: trades } = useQuery({ queryKey: ['backtest-trades', shown?.id], enabled: !!shown && shown.status === 'DONE',
    queryFn: () => request<BacktestTrade[]>(`/backtests/${shown!.id}/trades`) });
  const { data: regimes } = useQuery({ queryKey: ['backtest-regimes', shown?.id], enabled: !!shown && shown.status === 'DONE',
    queryFn: () => request<RegimeBreakdown>(`/backtests/${shown!.id}/regimes`) });
  const { data: comparison } = useQuery({ queryKey: ['compare', id, compareA, version?.version], enabled: compareA != null && !!version,
    queryFn: () => request<any>(`/strategies/${id}/versions/compare?a=${compareA}&b=${version!.version}`) });

  async function deploy() {
    await request(`/strategies/${id}/versions/${version!.version}/deployments`, { method: 'POST', body: { mode: 'PAPER', autonomyLevel: 0, params: { risk_rupees: 2000 } } });
    qc.invalidateQueries({ queryKey: ['deployments'] });
  }
  async function toggle(d: Deployment) {
    await request(`/deployments/${d.id}`, { method: 'PUT', body: { enabled: !d.enabled, reason: d.enabled ? 'paused from web' : undefined } });
    qc.invalidateQueries({ queryKey: ['deployments'] });
  }
  async function setStatus(status: string) {
    await request(`/strategies/${id}/versions/${version!.version}/status`, { method: 'POST', body: { status } }).catch((e) => alert(e.message));
    qc.invalidateQueries({ queryKey: ['versions', id] });
  }

  if (!strategy || !versions) return <p>Loading…</p>;
  return (
    <div>
      <h1>{strategy.slug} <small>({strategy.family})</small></h1>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        Version:
        <select value={version?.version ?? ''} onChange={(e) => setSelected(Number(e.target.value))}>
          {versions.map((v) => <option key={v.id} value={v.version}>v{v.version} — {v.status} — {v.changeNote}</option>)}
        </select>
        <Link to={`/lab?strategyId=${id}&version=${version?.version ?? ''}`}>Edit in Lab</Link>
        Compare with:
        <select value={compareA ?? ''} onChange={(e) => setCompareA(e.target.value ? Number(e.target.value) : null)}>
          <option value="">—</option>
          {versions.filter((v) => v.version !== version?.version).map((v) => <option key={v.id} value={v.version}>v{v.version}</option>)}
        </select>
      </div>
      {comparison && (
        <div style={{ marginTop: 8, padding: 8, background: '#f4f6f8' }}>
          <b>{comparison.verdict}</b>
          <table><tbody>{comparison.deltas.map((d: any) => <tr key={d.metric}><td>{d.metric}</td><td>{d.a ?? '—'}</td><td>{d.b ?? '—'}</td><td>{d.changePct != null ? `${d.changePct}%` : ''}</td></tr>)}</tbody></table>
        </div>
      )}
      {version && (
        <>
          <h3>Rules (v{version.version}, {version.status})</h3>
          <Rules definition={version.definition} />
          <div style={{ display: 'flex', gap: 8 }}>
            {['BACKTESTED', 'VALIDATED', 'PAPER', 'LIVE', 'PAUSED', 'RETIRED'].map((s) => <button key={s} onClick={() => setStatus(s)}>→ {s}</button>)}
          </div>
          <h3>Hejje Score {score?.breakdown ? <span data-testid="score-final">{score.breakdown.finalScore}</span> : '—'}</h3>
          {score?.breakdown && (
            <table>
              <tbody>
                {score.breakdown.components.map((c) => <tr key={c.name}><td>{c.name}</td><td>{(c.weight * 100).toFixed(0)}%</td><td>{c.score}</td><td>{c.contribution}</td></tr>)}
                <tr><td><b>Base</b></td><td></td><td></td><td>{score.breakdown.base} {score.breakdown.cap ? `(${score.breakdown.cap})` : ''}</td></tr>
                {score.breakdown.adjustments.map((a) => <tr key={a.name}><td>{a.name}</td><td></td><td></td><td>{a.delta >= 0 ? '+' : ''}{a.delta}</td></tr>)}
                <tr><td><b>Final</b></td><td></td><td></td><td><b>{score.breakdown.finalScore}</b></td></tr>
              </tbody>
            </table>
          )}
          <h3>Deployments</h3>
          <button onClick={deploy} disabled={!['PAPER', 'LIVE'].includes(version.status)}>Deploy (PAPER, ₹2,000 risk)</button>
          <ul>
            {(deployments ?? []).map((d) => (
              <li key={d.id}>{d.mode} · {d.instrumentIds.length} instrument(s) · {d.enabled ? 'enabled' : `paused (${d.pauseReason ?? ''})`}
                <button onClick={() => toggle(d)} style={{ marginLeft: 8 }}>{d.enabled ? 'Pause' : 'Enable'}</button></li>
            ))}
          </ul>
          <h3>Backtests</h3>
          <select value={shown?.id ?? ''} onChange={(e) => setBacktestId(e.target.value)}>
            {(backtests ?? []).map((b) => <option key={b.id} value={b.id}>{new Date(b.createdAt).toLocaleString()} — {b.status} {b.progressPct}%</option>)}
          </select>
          {shown && <Metrics b={shown} />}
          {regimes && <Regimes r={regimes} />}
          {shown?.metrics && <EquityChart points={shown.metrics.equityCurve} />}
          {shown?.warnings?.length ? <ul>{shown.warnings.map((w) => <li key={w.code} style={{ color: w.severity === 'FAIL' ? '#c0392b' : '#b7791f' }}>{w.code}: {w.message}</li>)}</ul> : null}
          {trades && (
            <table style={{ marginTop: 8 }}>
              <thead><tr><th>Entry</th><th>Exit</th><th>Side</th><th>Qty</th><th>In</th><th>Out</th><th>Net</th><th>R</th><th>Reason</th></tr></thead>
              <tbody>
                {trades.slice(0, 200).map((t) => (
                  <tr key={t.id}><td>{new Date(t.entryTime).toLocaleString()}</td><td>{new Date(t.exitTime).toLocaleTimeString()}</td><td>{t.side}</td><td>{t.qty}</td>
                    <td>{t.entryPrice}</td><td>{t.exitPrice}</td><td>{formatPaise(t.netPnl.paise)}</td><td>{formatR(t.rMultiple)}</td><td>{t.exitReason}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </div>
  );
}
