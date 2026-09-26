import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { request } from '../api/client';
import { Backtest, BacktestTrade, Deployment, EventRisk, RegimeBreakdown, ScoreView, Strategy, StrategyContext, StrategyVersion } from '../api/types';
import { ContextCard } from '../components/ContextCard';
import { DriftPanel } from '../components/DriftPanel';
import { NewsBiasPanel } from '../components/NewsBiasPanel';
import { formatPaise } from '../lib/sizing';
import { formatR } from '../lib/today';
import { EquityChart } from '../components/EquityChart';
import { Button, Card, Dialog, Field, Page, signed, tone } from '../ui';
import '../styles/research.css';

const net = (paise: number) => <span className={`tone-${tone(paise)}`}>{signed(paise / 100)}</span>;

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
  const stat = (label: string, value: React.ReactNode) => <div className="stat"><div className="stat-label">{label}</div><div className="stat-value num">{value}</div></div>;
  return (
    <div className="stat-grid">
      {stat('Trades', m.totalTrades)}{stat('Win rate', `${Math.round(m.winRate * 100)}%`)}
      {stat('Expectancy', <span className={`tone-${tone(m.expectancyR)}`}>{formatR(m.expectancyR)}</span>)}{stat('Profit factor', m.profitFactor?.toFixed(2) ?? '—')}
      {stat('Max DD', `-${m.maxDrawdownR.toFixed(1)}R`)}{stat('Sharpe', m.sharpe?.toFixed(2) ?? '—')}
      {stat('Gross', net(m.grossPnl.paise))}{stat('Costs', formatPaise(m.totalCosts.paise))}
      {stat('Net', net(m.netPnl.paise))}{stat('Sessions', `${b.sessionsWithData}/${b.sessionsExpected}`)}
    </div>
  );
}

function Regimes({ r }: { r: RegimeBreakdown }) {
  return (
    <div data-testid="regime-breakdown" className="stack-sm">
      <h4>By regime ({r.dims.join(' × ')})</h4>
      <p>
        {r.similar
          ? <>Similar regime <b>{r.similar.current}</b>: {r.similar.trades} trades, {formatR(r.similar.expectancyR)} expectancy vs {formatR(r.similar.overallExpectancyR)} overall</>
          : <>Similar regime: {r.note ?? 'n/a'}</>}
      </p>
      <div className="table-scroll">
        <table>
          <thead><tr><th>Regime</th><th className="num">Trades</th><th className="num">Win rate</th><th className="num">Expectancy</th><th className="num">PF</th><th className="num">Net</th></tr></thead>
          <tbody>
            {r.byRegime.map((b) => (
              <tr key={b.key}>
                <td>{b.key}</td><td className="num">{b.trades}</td><td className="num">{Math.round(b.winRate * 100)}%</td><td className="num">{formatR(b.expectancyR)}</td>
                <td className="num">{b.profitFactor?.toFixed(2) ?? '—'}</td><td className="num">{net(b.netPnl.paise)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function StrategyDetail() {
  const { id } = useParams();
  const qc = useQueryClient();
  const [selected, setSelected] = useState<number | null>(null);
  const [compareA, setCompareA] = useState<number | null>(null);
  const [backtestId, setBacktestId] = useState<string | null>(null);
  const [statusError, setStatusError] = useState('');
  const [confirmRetire, setConfirmRetire] = useState(false);
  const { data: strategy } = useQuery({ queryKey: ['strategy', id], queryFn: () => request<Strategy>(`/strategies/${id}`) });
  const { data: versions } = useQuery({ queryKey: ['versions', id], queryFn: () => request<StrategyVersion[]>(`/strategies/${id}/versions`) });
  const version = versions?.find((v) => v.version === (selected ?? strategy?.latestVersion));
  const { data: score } = useQuery({ queryKey: ['score', id, version?.version], enabled: !!version,
    queryFn: () => request<ScoreView>(`/strategies/${id}/score?version=${version!.version}`) });
  const { data: backtests } = useQuery({ queryKey: ['backtests', version?.id], enabled: !!version,
    queryFn: () => request<Backtest[]>(`/backtests?versionId=${version!.id}`), refetchInterval: 5000 });
  const { data: deployments } = useQuery({ queryKey: ['deployments', version?.id], enabled: !!version,
    queryFn: () => request<Deployment[]>(`/deployments?versionId=${version!.id}`) });
  const firstInstrument = deployments?.find((d) => d.instrumentIds.length > 0)?.instrumentIds[0];
  const { data: eventRisk } = useQuery({ queryKey: ['event-risk', firstInstrument], enabled: !!firstInstrument,
    queryFn: () => request<EventRisk>(`/events/risk?instrumentId=${firstInstrument}`), refetchInterval: 60_000 });
  const { data: contextCard } = useQuery({ queryKey: ['context', version?.id, firstInstrument], enabled: !!version && !!firstInstrument,
    queryFn: () => request<StrategyContext>(`/context/strategy?versionId=${version!.id}&instrumentId=${firstInstrument}`), refetchInterval: 60_000 });
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
    setConfirmRetire(false);
    setStatusError('');
    await request(`/strategies/${id}/versions/${version!.version}/status`, { method: 'POST', body: { status } }).catch((e) => setStatusError(e.message));
    qc.invalidateQueries({ queryKey: ['versions', id] });
  }

  if (!strategy || !versions) return <Page title="Strategy"><p>Loading…</p></Page>;
  return (
    <Page title={<>{strategy.slug} <small className="muted">({strategy.family})</small></>}
      actions={<Link to={`/lab?strategyId=${id}&version=${version?.version ?? ''}`}>Edit in Lab</Link>}>
      <Card>
        <div className="cluster">
          <Field label="Version">
            <select value={version?.version ?? ''} onChange={(e) => setSelected(Number(e.target.value))}>
              {versions.map((v) => <option key={v.id} value={v.version}>v{v.version} — {v.status} — {v.changeNote}</option>)}
            </select>
          </Field>
          <Field label="Compare with">
            <select value={compareA ?? ''} onChange={(e) => setCompareA(e.target.value ? Number(e.target.value) : null)}>
              <option value="">—</option>
              {versions.filter((v) => v.version !== version?.version).map((v) => <option key={v.id} value={v.version}>v{v.version}</option>)}
            </select>
          </Field>
        </div>
        {comparison && (
          <div className="panel section">
            <b>{comparison.verdict}</b>
            <div className="table-scroll">
              <table><tbody>{comparison.deltas.map((d: any) => <tr key={d.metric}><td>{d.metric}</td><td className="num">{d.a ?? '—'}</td><td className="num">{d.b ?? '—'}</td><td className="num">{d.changePct != null ? `${d.changePct}%` : ''}</td></tr>)}</tbody></table>
            </div>
          </div>
        )}
      </Card>
      {version && (
        <>
          <Card title={<>Rules (v{version.version}, {version.status})</>}>
            <div className="stack">
              <Rules definition={version.definition} />
              <div className="cluster">
                {['BACKTESTED', 'VALIDATED', 'PAPER', 'LIVE', 'PAUSED', 'RETIRED'].map((s) => (
                  <Button key={s} size="sm" variant={s === 'RETIRED' ? 'danger' : 'secondary'} onClick={() => (s === 'RETIRED' ? setConfirmRetire(true) : setStatus(s))}>→ {s}</Button>
                ))}
              </div>
              {statusError && <p className="message message-loss">{statusError}</p>}
            </div>
          </Card>
          <Card title={<>Hejje Score {score?.breakdown ? <span data-testid="score-final">{score.breakdown.finalScore}</span> : '—'}</>}>
            <div className="stack">
              {score?.breakdown && (
                <div className="table-scroll">
                  <table>
                    <tbody>
                      {score.breakdown.components.map((c) => <tr key={c.name}><td>{c.name}</td><td className="num">{(c.weight * 100).toFixed(0)}%</td><td className="num">{c.score}</td><td className="num">{c.contribution}</td></tr>)}
                      <tr><td><b>Base</b></td><td></td><td></td><td className="num">{score.breakdown.base} {score.breakdown.cap ? `(${score.breakdown.cap})` : ''}</td></tr>
                      {score.breakdown.adjustments.map((a) => <tr key={a.name}><td>{a.name}</td><td></td><td></td><td className="num">{a.delta >= 0 ? '+' : ''}{a.delta}</td></tr>)}
                      <tr><td><b>Final</b></td><td></td><td></td><td className="num"><b>{score.breakdown.finalScore}</b></td></tr>
                    </tbody>
                  </table>
                </div>
              )}
              {eventRisk && (
                <p data-testid="next-event">
                  Event risk <b>{eventRisk.level}</b>
                  {eventRisk.nextEvent ? <> · Next event — {eventRisk.nextEvent.title} {eventRisk.nextEvent.allDay ? new Date(eventRisk.nextEvent.startsAt).toLocaleDateString() : new Date(eventRisk.nextEvent.startsAt).toLocaleString()}</> : ' · no scheduled events'}
                </p>
              )}
              <NewsBiasPanel instrumentId={firstInstrument} />
              {contextCard && <ContextCard context={contextCard} />}
            </div>
          </Card>
          <Card title="Deployments" actions={<Button variant="primary" onClick={deploy} disabled={!['PAPER', 'LIVE'].includes(version.status)}>Deploy (PAPER, ₹2,000 risk)</Button>}>
            {(deployments ?? []).length === 0 ? <p className="muted">No deployments.</p> : (
              <ul className="plain-list">
                {(deployments ?? []).map((d) => (
                  <li key={d.id} className="cluster">{d.mode} · {d.instrumentIds.length} instrument(s) · {d.enabled ? 'enabled' : `paused (${d.pauseReason ?? ''})`}
                    {d.sizeMultiplier < 1 && <> · size ×{d.sizeMultiplier.toFixed(2)}</>}
                    <Button size="sm" onClick={() => toggle(d)}>{d.enabled ? 'Pause' : 'Enable'}</Button></li>
                ))}
              </ul>
            )}
          </Card>
          <DriftPanel strategyId={strategy.id} />
          <Card title="Backtests">
            <div className="stack">
              <Field label="Run">
                <select value={shown?.id ?? ''} onChange={(e) => setBacktestId(e.target.value)}>
                  {(backtests ?? []).map((b) => <option key={b.id} value={b.id}>{new Date(b.createdAt).toLocaleString()} — {b.status} {b.progressPct}%</option>)}
                </select>
              </Field>
              {shown && <Metrics b={shown} />}
              {regimes && <Regimes r={regimes} />}
              {shown?.metrics && <EquityChart points={shown.metrics.equityCurve} />}
              {shown?.warnings?.length ? <ul>{shown.warnings.map((w) => <li key={w.code} className={w.severity === 'FAIL' ? 'tone-loss' : 'tone-warning'}>{w.severity === 'FAIL' ? '✗' : '⚠'} {w.code}: {w.message}</li>)}</ul> : null}
              {trades && (
                <div className="table-scroll">
                  <table>
                    <thead><tr><th>Entry</th><th>Exit</th><th>Side</th><th className="num">Qty</th><th className="num">In</th><th className="num">Out</th><th className="num">Net</th><th className="num">R</th><th>Reason</th></tr></thead>
                    <tbody>
                      {trades.slice(0, 200).map((t) => (
                        <tr key={t.id}><td>{new Date(t.entryTime).toLocaleString()}</td><td>{new Date(t.exitTime).toLocaleTimeString()}</td><td>{t.side}</td><td className="num">{t.qty}</td>
                          <td className="num">{t.entryPrice}</td><td className="num">{t.exitPrice}</td><td className="num">{net(t.netPnl.paise)}</td><td className="num">{formatR(t.rMultiple)}</td><td>{t.exitReason}</td></tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </Card>
          <Dialog
            open={confirmRetire}
            title={`Retire v${version.version}?`}
            onClose={() => setConfirmRetire(false)}
            actions={<>
              <Button onClick={() => setConfirmRetire(false)}>Keep it</Button>
              <Button variant="danger" onClick={() => setStatus('RETIRED')}>Retire</Button>
            </>}
          >
            A retired version can no longer be deployed.
          </Dialog>
        </>
      )}
    </Page>
  );
}
