import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { AdherenceReport, CounterfactualReport, LossBucket, LossReport, Outcome, SlippageReport } from '../api/types';
import { monthToDate, rupees, toggle } from '../lib/performance';
import { Badge, Button, Card, Field } from '../ui';
import '../styles/research.css';

const DIMENSIONS = ['family', 'trend', 'event', 'news', 'exitReason'];

function Buckets({ name, buckets }: { name: string; buckets: LossBucket[] }) {
  return (
    <div className="table-scroll">
      <table data-testid={`losses-${name}`}>
        <thead><tr><th>{name}</th><th className="num">Trades</th><th className="num">Lost</th><th className="num">Losses</th><th className="num">Share</th></tr></thead>
        <tbody>{buckets.map((b) => (
          <tr key={b.key}><td>{b.key}</td><td className="num">{b.trades}</td><td className="num">{b.losers}</td><td className="num">{rupees(b.losses)}</td><td className="num">{b.lossSharePct}%</td></tr>
        ))}</tbody>
      </table>
    </div>
  );
}

function OutcomeRows({ actual, simulated }: { actual: Outcome; simulated: Outcome }) {
  const rows: [string, (o: Outcome) => string][] = [
    ['Trades', (o) => String(o.trades)], ['Net P&L', (o) => rupees(o.netPnl)], ['Max drawdown', (o) => rupees(o.maxDrawdown)],
    ['Win rate', (o) => (o.winRate === null ? '—' : `${Math.round(o.winRate * 100)}%`)], ['Profit factor', (o) => (o.profitFactor === null ? '—' : o.profitFactor.toFixed(2))],
  ];
  return <>{rows.map(([label, f]) => <tr key={label}><td>{label}</td><td className="num">{f(actual)}</td><td className="num simulated">{f(simulated)}</td></tr>)}</>;
}

/** Actual loss attribution, slippage and adherence, and a counterfactual shown strictly apart as SIMULATED (plan M4.5, PRD 57). */
export function LossInvestigation() {
  const [period, setPeriod] = useState(() => monthToDate(new Date()));
  const q = `from=${period.from}&to=${period.to}`;
  const { data: losses } = useQuery({ queryKey: ['losses', q], queryFn: () => request<LossReport>(`/analytics/losses?${q}`) });
  const { data: slippage } = useQuery({ queryKey: ['slippage', q], queryFn: () => request<SlippageReport>(`/analytics/slippage?${q}`) });
  const { data: adherence } = useQuery({ queryKey: ['adherence', q], queryFn: () => request<AdherenceReport>(`/analytics/adherence?${q}`) });
  const [families, setFamilies] = useState<string[]>([]);
  const [trends, setTrends] = useState<string[]>([]);
  const [cf, setCf] = useState<CounterfactualReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const dim = (name: string) => losses?.attribution.dimensions.find((d) => d.name === name)?.buckets ?? [];

  async function run() {
    try {
      setCf(await request<CounterfactualReport>('/analytics/counterfactual', { method: 'POST', body: { from: period.from, to: period.to, exclude: { families, trends } } }));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const a = losses?.attribution;
  return (
    <Card title="Loss investigation" data-testid="loss-investigation">
      <div className="stack">
        <div className="cluster">
          <Field label="From"><input type="date" value={period.from} onChange={(e) => setPeriod({ ...period, from: e.target.value })} /></Field>
          <Field label="To"><input type="date" value={period.to} onChange={(e) => setPeriod({ ...period, to: e.target.value })} /></Field>
        </div>
        <h3>Actual</h3>
        {a && (
          <p data-testid="losses-summary">{a.trades} closed trades, net <b>{rupees(a.netPnl)}</b>; {a.losers} losers lost {rupees(a.grossLosses)}, {a.winners} winners made {rupees(a.grossWins)}.
            {a.headline && <><br /><b>{a.headline}</b></>}</p>
        )}
        <div className="grid-auto">{DIMENSIONS.map((d) => <Buckets key={d} name={d} buckets={dim(d)} />)}</div>
        {slippage && (
          <p>Slippage: entry {slippage.slippage.entry.meanBps ?? '—'} bps mean over {slippage.slippage.entry.trades} trades, exit {slippage.slippage.exit.meanBps ?? '—'} bps over{' '}
            {slippage.slippage.exit.trades}; estimated cost {rupees(slippage.slippage.totalCostRupees)}.</p>
        )}
        {adherence && (
          <p>Rule adherence: {adherence.adherence.meanAdherencePct ?? '—'}% mean over {adherence.adherence.withAdherence} reviewed trades; {adherence.adherence.fullAdherence} fully adherent,{' '}
            {adherence.adherence.setupInvalid} invalid setups, {adherence.adherence.manualExits} manual exits.</p>
        )}
        <h3 className="cluster">Counterfactual <Badge tone="warning">SIMULATED</Badge></h3>
        <p className="muted text-sm">Remove the trades matching every selected category from the actual sequence. Hypothetical — not what happened.</p>
        <div className="cluster">
          <div className="cluster">Families: {dim('family').map((b) => (
            <label key={b.key}><input type="checkbox" checked={families.includes(b.key)} onChange={() => setFamilies(toggle(families, b.key))} />{b.key}</label>
          ))}</div>
          <div className="cluster">Trends: {dim('trend').map((b) => (
            <label key={b.key}><input type="checkbox" checked={trends.includes(b.key)} onChange={() => setTrends(toggle(trends, b.key))} />{b.key}</label>
          ))}</div>
          <Button disabled={!families.length && !trends.length} onClick={run}>Run</Button>
        </div>
        {error && <p className="message message-loss">{error}</p>}
        {cf && (
          <div className="table-scroll">
            <table data-testid="counterfactual">
              <thead><tr><th>{cf.counterfactual.excludedTrades} trades removed</th><th className="num">Actual</th><th className="num simulated">Simulated (hypothetical)</th></tr></thead>
              <tbody><OutcomeRows actual={cf.counterfactual.actual} simulated={cf.counterfactual.simulated} /></tbody>
              <tfoot><tr><td colSpan={3} className="muted">{cf.counterfactual.basis}: {cf.counterfactual.note}</td></tr></tfoot>
            </table>
          </div>
        )}
      </div>
    </Card>
  );
}
