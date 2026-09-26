import { Fragment, ReactNode, useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ColumnDef } from '@tanstack/react-table';
import { Link } from 'react-router-dom';
import { ApiError, request } from '../api/client';
import { MarketEvent, PreparedOrder, Recommendation, TodayView } from '../api/types';
import { decisionTone, formatR, rewardRisk, secondsLeft } from '../lib/today';
import { NewsBiasPanel } from '../components/NewsBiasPanel';
import { ContextCard } from '../components/ContextCard';
import { CandidateContextLine, useCandidatesContext } from './Stock';
import { Badge, Button, Card, DataTable, Dialog, EmptyState, Page, PHONE, useMediaQuery } from '../ui';
import '../styles/trading.css';

function Header({ view }: { view: TodayView }) {
  const q = view.header.indexQuotes ?? {};
  return (
    <Card data-testid="today-header">
      <div className="today-header">
        {Object.entries(q).map(([symbol, quote]) => (
          <div key={symbol}><b>{symbol.replace('INDEX:', '')}</b> <span className="num">{quote.lastPrice}</span>{quote.stale ? ' (stale)' : ''}</div>
        ))}
        {Object.keys(q).length === 0 && <div className="muted">No index quotes (market stream off)</div>}
        <div>Regime <b>{view.header.regime ?? '—'}</b></div>
        <div>Breadth <b>{view.header.breadth ?? '—'}</b></div>
        <div>Event risk <b>{view.header.eventRisk ?? '—'}</b>{view.header.nextEvent ? <> · {view.header.nextEvent}</> : null}</div>
      </div>
    </Card>
  );
}

/** The next seven days of the calendar (PRD 18): market events plus every instrument event. */
function Calendar() {
  const { data } = useQuery({ queryKey: ['events-week'], queryFn: () => request<MarketEvent[]>('/events?all=true'), refetchInterval: 300_000 });
  if (!data) return null;
  const columns: ColumnDef<MarketEvent, any>[] = [
    { id: 'when', header: 'When', accessorFn: (e) => e.startsAt,
      cell: (c) => (c.row.original.allDay ? new Date(c.getValue()).toLocaleDateString() : new Date(c.getValue()).toLocaleString()) },
    { accessorKey: 'type', header: 'Type' },
    { id: 'symbol', header: 'Symbol', accessorFn: (e) => e.symbol ?? 'Market' },
    { accessorKey: 'title', header: 'Title' },
    { accessorKey: 'source', header: 'Source', cell: (c) => <span className="muted">{c.getValue()}</span> },
  ];
  return (
    <Card title="Events (next 7 days)" data-testid="event-calendar">
      <DataTable columns={columns} data={data.slice(0, 30)} empty="No scheduled events." getRowId={(e) => e.id} />
    </Card>
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
    <Dialog
      open
      data-testid="execute-modal"
      title={<>Prepare order — {rec.instrument}</>}
      onClose={() => onClose(false)}
      actions={<>
        <Button onClick={() => onClose(false)}>Cancel</Button>
        <Button variant="primary" data-testid="confirm-execute" disabled={busy || !prepared || prepared.risk.outcome !== 'APPROVED'} onClick={confirm}>CONFIRM</Button>
      </>}
    >
      <div className="stack">
        {!prepared && !error && <p>Preparing…</p>}
        {prepared && (
          <>
            <dl className="kv">
              <dt>Side</dt><dd data-testid="proposal-side">{prepared.proposal.side}</dd>
              <dt>Quantity</dt><dd className="num" data-testid="proposal-quantity">{prepared.proposal.quantity}</dd>
              <dt>Type</dt><dd>{prepared.proposal.orderType} / {prepared.proposal.product}</dd>
              <dt>Stop</dt><dd className="num">{prepared.proposal.stopPrice}</dd>
              <dt>Target</dt><dd className="num">{prepared.proposal.targetPrice ?? '—'}</dd>
              <dt>Max risk</dt><dd className="num">₹{((prepared.proposal.maxRisk?.paise ?? 0) / 100).toFixed(2)}</dd>
            </dl>
            <div>
              <h4>Risk checks — <Badge tone={prepared.risk.outcome === 'APPROVED' ? 'profit' : 'loss'} data-testid="risk-outcome">{prepared.risk.outcome}</Badge></h4>
              <ul className="checks execute-checks">
                {prepared.risk.checks.map((c) => (
                  <li key={c.name} className={c.passed ? 'check-pass' : 'check-fail'}>{c.passed ? '✓' : '✗'} {c.name}: {c.message}</li>
                ))}
              </ul>
            </div>
            {prepared.notes.map((n) => <p key={n} className="message message-loss">{n}</p>)}
          </>
        )}
        {error && <p data-testid="execute-error" className="message message-loss">{error}</p>}
      </div>
    </Dialog>
  );
}

function BestCard({ rec, onExecute, onSkip }: { rec: Recommendation; onExecute: () => void; onSkip: () => void }) {
  const [details, setDetails] = useState(false);
  const rr = rewardRisk(rec);
  const bt = rec.backtest as Record<string, any>;
  const sb = rec.scoreBreakdown as Record<string, any>;
  const rows: [string, ReactNode][] = [
    ['Direction', rec.direction === 'BUY' ? 'LONG' : 'SHORT'],
    ['Signal', `${rec.signalStatus} (${secondsLeft(rec.signalValidUntil)}s left)`],
    ['Backtested expectancy', formatR(bt.expectancyR)],
    ['Historical win rate', bt.winRatePct != null ? `${bt.winRatePct}%` : '—'],
    ['Profit factor', bt.profitFactor ?? '—'],
    ['Max drawdown', bt.maxDrawdownR != null ? `-${bt.maxDrawdownR}R` : '—'],
    ...(sb.adjustments ?? []).map((a: any): [string, string] => [a.name, `${a.delta >= 0 ? '+' : ''}${a.delta}`]),
    ['Entry', rec.entry],
    ['Stop', rec.stop],
    ['Target', <>{rec.target ?? '—'} {rr != null && `(${rr}R)`}</>],
    ['Risk', `₹${rec.riskRupees}`],
    ['Expected reward', rec.expectedRewardRupees != null ? `₹${rec.expectedRewardRupees}` : '—'],
    ['Event risk', `${rec.eventRisk}${rec.nextEvent ? ` — ${rec.nextEvent}` : ''}`],
    ['Regime', rec.regime ?? '—'],
  ];
  return (
    <Card data-testid="best-card" className="best-card" title={<>{rec.instrument} — {rec.strategy} v{rec.version}</>}>
      <div className="stack">
        <div className="best-score">HEJJE SCORE <span data-testid="best-score">{rec.score ?? '—'}</span> / 100</div>
        <dl className="kv">
          {rows.map(([k, v], i) => <Fragment key={`${k}-${i}`}><dt>{k}</dt><dd>{v}</dd></Fragment>)}
        </dl>
        <div><Badge tone={decisionTone(rec.decision)} className="best-decision" data-testid="best-decision">{rec.decision.replace(/_/g, ' ')}</Badge></div>
        {rec.cautions.length > 0 && <ul data-testid="best-cautions">{rec.cautions.map((c) => <li key={c.code}>⚠ {c.message}</li>)}</ul>}
        <NewsBiasPanel instrumentId={rec.instrumentId} />
        {rec.context && <ContextCard context={rec.context} />}
        <div className="cluster">
          <Button variant="primary" data-testid="execute-button" onClick={onExecute}>EXECUTE</Button>
          <Button onClick={() => setDetails(!details)} aria-expanded={details}>DETAILS</Button>
          <Button data-testid="skip-button" onClick={onSkip}>SKIP</Button>
        </div>
        {details && (
          <div>
            <h4>Supporting evidence</h4>
            <ul>{rec.supportingEvidence.map((e) => <li key={e}>{e}</li>)}</ul>
            <h4>Risks</h4>
            <ul>{rec.risks.map((e) => <li key={e}>{e}</li>)}</ul>
            <Link to={`/strategies/${rec.strategyId}`}>Strategy page</Link>
          </div>
        )}
      </div>
    </Card>
  );
}

const why = (r: Recommendation) => [...r.hardBlocks, ...r.risks].slice(0, 2).join('; ');
const direction = (r: Recommendation) => (r.direction === 'BUY' ? 'Long' : r.direction === 'SELL' ? 'Short' : '—');

export function Today() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['today'], queryFn: () => request<TodayView>('/today'), refetchInterval: 5000 });
  const [executing, setExecuting] = useState<Recommendation | null>(null);
  const [message, setMessage] = useState('');
  const context = useCandidatesContext((data?.ranked ?? []).map((r) => r.instrument));
  const phone = useMediaQuery(PHONE);

  async function skip(rec: Recommendation) {
    await request(`/signals/${rec.signalId}/skip`, { method: 'POST', body: { reason: 'skipped from Today' } });
    qc.invalidateQueries({ queryKey: ['today'] });
  }

  const ranked = data?.ranked ?? [];
  const contextLine = (r: Recommendation) => (
    <CandidateContextLine symbol={r.instrument} rating={context.ratings[r.instrument]} session={context.sessions[r.instrument]} />
  );
  const columns: ColumnDef<Recommendation, any>[] = [
    { id: 'rank', header: '#', accessorFn: (_r, i) => i + 1, meta: { numeric: true } },
    { accessorKey: 'instrument', header: 'Instrument', cell: (c) => <>{c.getValue()}{contextLine(c.row.original)}</> },
    { id: 'strategy', header: 'Strategy', accessorFn: (r) => `${r.strategy} v${r.version}` },
    { id: 'score', header: 'Score', accessorFn: (r) => r.score ?? null, meta: { numeric: true }, cell: (c) => c.getValue() ?? '—' },
    { id: 'direction', header: 'Direction', accessorFn: direction },
    { accessorKey: 'decision', header: 'Decision', cell: (c) => <Badge tone={decisionTone(c.getValue())}>{c.getValue()}</Badge> },
    { id: 'why', header: 'Why', enableSorting: false, accessorFn: why, cell: (c) => <span className="why">{c.getValue()}</span> },
  ];

  return (
    <Page title="Today">
      {data && <Header view={data} />}
      {data?.best && (
        <BestCard rec={data.best} onExecute={() => setExecuting(data.best!)} onSkip={() => skip(data.best!)} />
      )}
      {data && !data.best && <p data-testid="no-trade" className="today-no-trade">{data.noTrade}</p>}
      {message && <p data-testid="today-message" className="message message-profit">{message}</p>}
      {executing && (
        <ExecuteModal rec={executing} onClose={(done) => {
          setExecuting(null);
          if (done) { setMessage('Order submitted'); qc.invalidateQueries({ queryKey: ['today'] }); }
        }} />
      )}
      <Card title="Ranked opportunities">
        {phone ? (
          ranked.length === 0 ? <EmptyState title="No opportunities yet" /> : (
            <ol className="item-cards ranked-cards">
              {ranked.map((r) => (
                <li key={`${r.deploymentId}-${r.instrumentId}`} className="item-card">
                  <div className="item-card-head"><span>{r.instrument}</span><Badge tone={decisionTone(r.decision)}>{r.decision}</Badge></div>
                  <div className="text-sm">{r.strategy} v{r.version} · {direction(r)} · score <span className="num">{r.score ?? '—'}</span></div>
                  {contextLine(r)}
                  {why(r) && <div className="why">{why(r)}</div>}
                </li>
              ))}
            </ol>
          )
        ) : (
          <DataTable data-testid="ranked-table" columns={columns} data={ranked} loading={!data} empty="No opportunities yet"
            getRowId={(r) => `${r.deploymentId}-${r.instrumentId}`} />
        )}
      </Card>
      <Calendar />
    </Page>
  );
}
