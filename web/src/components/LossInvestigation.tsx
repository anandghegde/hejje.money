import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { AdherenceReport, CounterfactualReport, LossBucket, LossReport, Outcome, SlippageReport } from '../api/types';
import { monthToDate, rupees, toggle } from '../lib/performance';

const DIMENSIONS = ['family', 'trend', 'event', 'news', 'exitReason'];

function Buckets({ name, buckets }: { name: string; buckets: LossBucket[] }) {
  return (
    <table style={{ fontSize: 13, marginRight: 24 }} data-testid={`losses-${name}`}>
      <thead><tr><th align="left">{name}</th><th>Trades</th><th>Lost</th><th>Losses</th><th>Share</th></tr></thead>
      <tbody>{buckets.map((b) => (
        <tr key={b.key}><td>{b.key}</td><td align="right">{b.trades}</td><td align="right">{b.losers}</td><td align="right">{rupees(b.losses)}</td><td align="right">{b.lossSharePct}%</td></tr>
      ))}</tbody>
    </table>
  );
}

function OutcomeRows({ actual, simulated }: { actual: Outcome; simulated: Outcome }) {
  const rows: [string, (o: Outcome) => string][] = [
    ['Trades', (o) => String(o.trades)], ['Net P&L', (o) => rupees(o.netPnl)], ['Max drawdown', (o) => rupees(o.maxDrawdown)],
    ['Win rate', (o) => (o.winRate === null ? '—' : `${Math.round(o.winRate * 100)}%`)], ['Profit factor', (o) => (o.profitFactor === null ? '—' : o.profitFactor.toFixed(2))],
  ];
  return <>{rows.map(([label, f]) => <tr key={label}><td>{label}</td><td align="right">{f(actual)}</td><td align="right" style={{ background: '#fff3e0' }}>{f(simulated)}</td></tr>)}</>;
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
    <section data-testid="loss-investigation" style={{ marginTop: 32 }}>
      <h2>Loss investigation</h2>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <label>from <input type="date" value={period.from} onChange={(e) => setPeriod({ ...period, from: e.target.value })} /></label>
        <label>to <input type="date" value={period.to} onChange={(e) => setPeriod({ ...period, to: e.target.value })} /></label>
      </div>
      <h3>Actual</h3>
      {a && (
        <p data-testid="losses-summary">{a.trades} closed trades, net <b>{rupees(a.netPnl)}</b>; {a.losers} losers lost {rupees(a.grossLosses)}, {a.winners} winners made {rupees(a.grossWins)}.
          {a.headline && <><br /><b>{a.headline}</b></>}</p>
      )}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>{DIMENSIONS.map((d) => <Buckets key={d} name={d} buckets={dim(d)} />)}</div>
      {slippage && (
        <p>Slippage: entry {slippage.slippage.entry.meanBps ?? '—'} bps mean over {slippage.slippage.entry.trades} trades, exit {slippage.slippage.exit.meanBps ?? '—'} bps over{' '}
          {slippage.slippage.exit.trades}; estimated cost {rupees(slippage.slippage.totalCostRupees)}.</p>
      )}
      {adherence && (
        <p>Rule adherence: {adherence.adherence.meanAdherencePct ?? '—'}% mean over {adherence.adherence.withAdherence} reviewed trades; {adherence.adherence.fullAdherence} fully adherent,{' '}
          {adherence.adherence.setupInvalid} invalid setups, {adherence.adherence.manualExits} manual exits.</p>
      )}
      <h3>Counterfactual <span style={{ background: '#ef6c00', color: '#fff', borderRadius: 4, padding: '0 6px', fontSize: 12 }}>SIMULATED</span></h3>
      <p style={{ fontSize: 13, color: '#616161' }}>Remove the trades matching every selected category from the actual sequence. Hypothetical — not what happened.</p>
      <div style={{ display: 'flex', gap: 24 }}>
        <div>Families: {dim('family').map((b) => (
          <label key={b.key} style={{ marginRight: 8 }}><input type="checkbox" checked={families.includes(b.key)} onChange={() => setFamilies(toggle(families, b.key))} />{b.key}</label>
        ))}</div>
        <div>Trends: {dim('trend').map((b) => (
          <label key={b.key} style={{ marginRight: 8 }}><input type="checkbox" checked={trends.includes(b.key)} onChange={() => setTrends(toggle(trends, b.key))} />{b.key}</label>
        ))}</div>
        <button disabled={!families.length && !trends.length} onClick={run}>Run</button>
      </div>
      {error && <p style={{ color: '#c62828' }}>{error}</p>}
      {cf && (
        <table data-testid="counterfactual" style={{ marginTop: 8, fontSize: 13 }}>
          <thead><tr><th align="left">{cf.counterfactual.excludedTrades} trades removed</th><th>Actual</th><th style={{ background: '#fff3e0' }}>Simulated (hypothetical)</th></tr></thead>
          <tbody><OutcomeRows actual={cf.counterfactual.actual} simulated={cf.counterfactual.simulated} /></tbody>
          <tfoot><tr><td colSpan={3} style={{ color: '#616161' }}>{cf.counterfactual.basis}: {cf.counterfactual.note}</td></tr></tfoot>
        </table>
      )}
    </section>
  );
}
