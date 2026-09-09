import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ApiError, request } from '../api/client';
import { MarketEvent, PreparedOrder, Recommendation, TodayView } from '../api/types';
import { decisionColor, formatR, rewardRisk, secondsLeft } from '../lib/today';
import { NewsBiasPanel } from '../components/NewsBiasPanel';
import { ContextCard } from '../components/ContextCard';

function Header({ view }: { view: TodayView }) {
  const q = view.header.indexQuotes ?? {};
  return (
    <div data-testid="today-header" style={{ display: 'flex', gap: 24, padding: 12, background: '#f4f6f8', borderRadius: 6 }}>
      {Object.entries(q).map(([symbol, quote]) => (
        <div key={symbol}><b>{symbol.replace('INDEX:', '')}</b> {quote.lastPrice}{quote.stale ? ' (stale)' : ''}</div>
      ))}
      {Object.keys(q).length === 0 && <div>No index quotes (market stream off)</div>}
      <div>Regime <b>{view.header.regime ?? '—'}</b></div>
      <div>Breadth <b>{view.header.breadth ?? '—'}</b></div>
      <div>Event risk <b>{view.header.eventRisk ?? '—'}</b>{view.header.nextEvent ? <> · {view.header.nextEvent}</> : null}</div>
    </div>
  );
}

/** The next seven days of the calendar (PRD 18): market events plus every instrument event. */
function Calendar() {
  const { data } = useQuery({ queryKey: ['events-week'], queryFn: () => request<MarketEvent[]>('/events?all=true'), refetchInterval: 300_000 });
  if (!data) return null;
  return (
    <div data-testid="event-calendar" style={{ marginTop: 16 }}>
      <h3>Events (next 7 days)</h3>
      {data.length === 0 ? <p>No scheduled events.</p> : (
        <table>
          <tbody>
            {data.slice(0, 30).map((e) => (
              <tr key={e.id}>
                <td>{e.allDay ? new Date(e.startsAt).toLocaleDateString() : new Date(e.startsAt).toLocaleString()}</td>
                <td>{e.type}</td><td>{e.symbol ?? 'Market'}</td><td>{e.title}</td><td style={{ color: '#888' }}>{e.source}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function ExecuteModal({ rec, onClose }: { rec: Recommendation; onClose: (executed: boolean) => void }) {
  const [prepared, setPrepared] = useState<PreparedOrder | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    request<PreparedOrder>(`/signals/${rec.signalId}/prepare`, { method: 'POST' }).then(setPrepared).catch((e) => setError(e.message));
  }, [rec.signalId]);

  async function confirm() {
    setBusy(true);
    setError('');
    try {
      await request(`/signals/${rec.signalId}/execute`, { method: 'POST', idempotent: true });
      onClose(true);
    } catch (e) {
      const problem = e instanceof ApiError ? e.problem : null;
      const failed = problem?.checks?.filter((c: any) => !c.passed).map((c: any) => `${c.name}: ${c.message}`);
      setError(failed?.length ? failed.join('; ') : (e as Error).message);
      setBusy(false);
    }
  }

  return (
    <div data-testid="execute-modal" style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ background: '#fff', padding: 24, borderRadius: 8, minWidth: 520 }}>
        <h2 style={{ marginTop: 0 }}>Prepare order — {rec.instrument}</h2>
        {!prepared && !error && <p>Preparing…</p>}
        {prepared && (
          <>
            <table>
              <tbody>
                <tr><td>Side</td><td data-testid="proposal-side">{prepared.proposal.side}</td></tr>
                <tr><td>Quantity</td><td data-testid="proposal-quantity">{prepared.proposal.quantity}</td></tr>
                <tr><td>Type</td><td>{prepared.proposal.orderType} / {prepared.proposal.product}</td></tr>
                <tr><td>Stop</td><td>{prepared.proposal.stopPrice}</td></tr>
                <tr><td>Target</td><td>{prepared.proposal.targetPrice ?? '—'}</td></tr>
                <tr><td>Max risk</td><td>₹{((prepared.proposal.maxRisk?.paise ?? 0) / 100).toFixed(2)}</td></tr>
              </tbody>
            </table>
            <h4>Risk checks — <span data-testid="risk-outcome">{prepared.risk.outcome}</span></h4>
            <ul style={{ maxHeight: 180, overflow: 'auto' }}>
              {prepared.risk.checks.map((c) => (
                <li key={c.name} style={{ color: c.passed ? '#1a9f57' : '#c0392b' }}>{c.passed ? '✓' : '✗'} {c.name}: {c.message}</li>
              ))}
            </ul>
            {prepared.notes.map((n) => <p key={n} style={{ color: '#c0392b' }}>{n}</p>)}
          </>
        )}
        {error && <p data-testid="execute-error" style={{ color: '#c0392b' }}>{error}</p>}
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button onClick={() => onClose(false)}>Cancel</button>
          <button data-testid="confirm-execute" disabled={busy || !prepared || prepared.risk.outcome !== 'APPROVED'} onClick={confirm}>CONFIRM</button>
        </div>
      </div>
    </div>
  );
}

function BestCard({ rec, onExecute, onSkip }: { rec: Recommendation; onExecute: () => void; onSkip: () => void }) {
  const [details, setDetails] = useState(false);
  const rr = rewardRisk(rec);
  const bt = rec.backtest as Record<string, any>;
  const sb = rec.scoreBreakdown as Record<string, any>;
  return (
    <div data-testid="best-card" style={{ border: '2px solid #1a9f57', borderRadius: 8, padding: 16, marginTop: 16, maxWidth: 640 }}>
      <h2 style={{ margin: 0 }}>{rec.instrument} — {rec.strategy} v{rec.version}</h2>
      <div style={{ fontSize: 28, fontWeight: 700, margin: '8px 0' }}>HEJJE SCORE <span data-testid="best-score">{rec.score ?? '—'}</span> / 100</div>
      <table style={{ width: '100%' }}>
        <tbody>
          <tr><td>Direction</td><td>{rec.direction === 'BUY' ? 'LONG' : 'SHORT'}</td></tr>
          <tr><td>Signal</td><td>{rec.signalStatus} ({secondsLeft(rec.signalValidUntil)}s left)</td></tr>
          <tr><td>Backtested expectancy</td><td>{formatR(bt.expectancyR)}</td></tr>
          <tr><td>Historical win rate</td><td>{bt.winRatePct != null ? `${bt.winRatePct}%` : '—'}</td></tr>
          <tr><td>Profit factor</td><td>{bt.profitFactor ?? '—'}</td></tr>
          <tr><td>Max drawdown</td><td>{bt.maxDrawdownR != null ? `-${bt.maxDrawdownR}R` : '—'}</td></tr>
          {(sb.adjustments ?? []).map((a: any) => <tr key={a.name}><td>{a.name}</td><td>{a.delta >= 0 ? '+' : ''}{a.delta}</td></tr>)}
          <tr><td>Entry</td><td>{rec.entry}</td></tr>
          <tr><td>Stop</td><td>{rec.stop}</td></tr>
          <tr><td>Target</td><td>{rec.target ?? '—'} {rr != null && `(${rr}R)`}</td></tr>
          <tr><td>Risk</td><td>₹{rec.riskRupees}</td></tr>
          <tr><td>Expected reward</td><td>{rec.expectedRewardRupees != null ? `₹${rec.expectedRewardRupees}` : '—'}</td></tr>
          <tr><td>Event risk</td><td>{rec.eventRisk}{rec.nextEvent ? ` — ${rec.nextEvent}` : ''}</td></tr>
          <tr><td>Regime</td><td>{rec.regime ?? '—'}</td></tr>
        </tbody>
      </table>
      <div data-testid="best-decision" style={{ color: decisionColor(rec.decision), fontWeight: 700, marginTop: 8 }}>{rec.decision.replace(/_/g, ' ')}</div>
      {rec.cautions.length > 0 && <ul data-testid="best-cautions">{rec.cautions.map((c) => <li key={c.code}>⚠ {c.message}</li>)}</ul>}
      <NewsBiasPanel instrumentId={rec.instrumentId} />
      {rec.context && <ContextCard context={rec.context} />}
      <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
        <button data-testid="execute-button" onClick={onExecute} style={{ background: '#1a9f57', color: '#fff', padding: '8px 16px' }}>EXECUTE</button>
        <button onClick={() => setDetails(!details)}>DETAILS</button>
        <button data-testid="skip-button" onClick={onSkip}>SKIP</button>
      </div>
      {details && (
        <div style={{ marginTop: 12 }}>
          <h4>Supporting evidence</h4>
          <ul>{rec.supportingEvidence.map((e) => <li key={e}>{e}</li>)}</ul>
          <h4>Risks</h4>
          <ul>{rec.risks.map((e) => <li key={e}>{e}</li>)}</ul>
          <Link to={`/strategies/${rec.strategyId}`}>Strategy page</Link>
        </div>
      )}
    </div>
  );
}

export function Today() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['today'], queryFn: () => request<TodayView>('/today'), refetchInterval: 5000 });
  const [executing, setExecuting] = useState<Recommendation | null>(null);
  const [message, setMessage] = useState('');

  async function skip(rec: Recommendation) {
    await request(`/signals/${rec.signalId}/skip`, { method: 'POST', body: { reason: 'skipped from Today' } });
    qc.invalidateQueries({ queryKey: ['today'] });
  }

  return (
    <div>
      <h1>Today</h1>
      {data && <Header view={data} />}
      {data?.best && (
        <BestCard rec={data.best} onExecute={() => setExecuting(data.best!)} onSkip={() => skip(data.best!)} />
      )}
      {data && !data.best && <p data-testid="no-trade" style={{ marginTop: 16, fontSize: 18 }}>{data.noTrade}</p>}
      {message && <p data-testid="today-message">{message}</p>}
      {executing && (
        <ExecuteModal rec={executing} onClose={(done) => {
          setExecuting(null);
          if (done) { setMessage('Order submitted'); qc.invalidateQueries({ queryKey: ['today'] }); }
        }} />
      )}
      <h3 style={{ marginTop: 24 }}>Ranked opportunities</h3>
      <table data-testid="ranked-table">
        <thead><tr><th>#</th><th>Instrument</th><th>Strategy</th><th>Score</th><th>Direction</th><th>Decision</th><th>Why</th></tr></thead>
        <tbody>
          {(data?.ranked ?? []).map((r, i) => (
            <tr key={`${r.deploymentId}-${r.instrumentId}`}>
              <td>{i + 1}</td><td>{r.instrument}</td><td>{r.strategy} v{r.version}</td><td>{r.score ?? '—'}</td>
              <td>{r.direction === 'BUY' ? 'Long' : r.direction === 'SELL' ? 'Short' : '—'}</td>
              <td style={{ color: decisionColor(r.decision), fontWeight: 700 }}>{r.decision}</td>
              <td style={{ fontSize: 12 }}>{[...r.hardBlocks, ...r.risks].slice(0, 2).join('; ')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Calendar />
    </div>
  );
}
