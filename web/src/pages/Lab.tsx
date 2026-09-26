import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError, request } from '../api/client';
import { Backtest, Strategy, StrategyVersion, ValidationReport } from '../api/types';
import { DescribeStrategy } from '../components/DescribeStrategy';
import { Experiments } from '../components/Experiments';
import { LegBuilder } from '../components/LegBuilder';
import { Button, Card, Dialog, Field, Page } from '../ui';
import '../styles/research.css';

const TEMPLATE = `name: my_orb
family: index
universe:
  - NIFTY
timeframe: 5m
direction: long
entry:
  all:
    - close > opening_range_high
    - close > vwap
stop:
  type: opening_range_low
target:
  type: risk_multiple
  value: 2
trade_window:
  start: "09:30"
  end: "12:00"
max_trades_per_day: 1
`;

export function Lab() {
  const [params] = useSearchParams();
  const qc = useQueryClient();
  const strategyId = params.get('strategyId');
  const [yaml, setYaml] = useState(TEMPLATE);
  const [report, setReport] = useState<ValidationReport | null>(null);
  const [changeNote, setChangeNote] = useState('');
  const [message, setMessage] = useState('');
  const [from, setFrom] = useState('2024-01-01');
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const [splits, setSplits] = useState('FIXED');
  const [instrument, setInstrument] = useState('');
  const [cloning, setCloning] = useState(false);
  const [cloneName, setCloneName] = useState('');
  const { data: strategy } = useQuery({ queryKey: ['strategy', strategyId], enabled: !!strategyId, queryFn: () => request<Strategy>(`/strategies/${strategyId}`) });
  const { data: versions } = useQuery({ queryKey: ['versions', strategyId], enabled: !!strategyId, queryFn: () => request<StrategyVersion[]>(`/strategies/${strategyId}/versions`) });
  const latest = versions?.[versions.length - 1];
  const { data: backtests } = useQuery({ queryKey: ['backtests', latest?.id], enabled: !!latest, refetchInterval: 3000,
    queryFn: () => request<Backtest[]>(`/backtests?versionId=${latest!.id}`) });

  useEffect(() => {
    const wanted = params.get('version');
    const v = versions?.find((x) => String(x.version) === wanted) ?? latest;
    if (v) setYaml(v.definitionYaml);
  }, [versions, params, latest]);

  useEffect(() => {
    const handle = setTimeout(() => {
      request<ValidationReport>('/strategies/validate', { method: 'POST', body: { yaml } }).then(setReport).catch(() => setReport(null));
    }, 400);
    return () => clearTimeout(handle);
  }, [yaml]);

  async function save() {
    setMessage('');
    try {
      if (strategyId) {
        const v = await request<StrategyVersion>(`/strategies/${strategyId}/versions`, { method: 'POST', body: { yaml, changeNote } });
        setMessage(`Saved v${v.version}`);
        qc.invalidateQueries({ queryKey: ['versions', strategyId] });
      } else {
        const v = await request<StrategyVersion>('/strategies', { method: 'POST', body: { yaml, changeNote } });
        setMessage(`Created strategy ${v.strategyId} v1`);
        qc.invalidateQueries({ queryKey: ['strategies'] });
      }
    } catch (e) {
      setMessage(e instanceof ApiError ? `${e.message}: ${(e.problem?.errors ?? []).map((x: any) => `${x.path}: ${x.message}`).join('; ')}` : (e as Error).message);
    }
  }
  async function clone() {
    const name = cloneName.trim();
    setCloning(false);
    if (!name || !strategyId) return;
    const v = await request<StrategyVersion>(`/strategies/${strategyId}/clone`, { method: 'POST', body: { name } });
    setMessage(`Cloned as ${v.strategyId}`);
    setCloneName('');
  }
  async function runBacktest() {
    if (!latest) return;
    setMessage('');
    try {
      const b = await request<Backtest>('/backtests', { method: 'POST', body: {
        versionId: latest.id, from, to, instruments: instrument ? [instrument] : undefined,
        splits: splits === 'WALK_FORWARD' ? { type: 'WALK_FORWARD', trainMonths: 6, testMonths: 2, anchored: false } : { type: splits },
      } });
      setMessage(`Backtest ${b.status}`);
      qc.invalidateQueries({ queryKey: ['backtests', latest.id] });
    } catch (e) { setMessage((e as Error).message); }
  }

  return (
    <Page title={<>Lab {strategy ? `— ${strategy.slug}` : ''}</>}>
      <DescribeStrategy strategyId={strategyId} />
      <div className="lab-grid">
        <Card title="Definition">
          <div className="stack">
            <textarea data-testid="yaml-editor" aria-label="strategy YAML" className="yaml-editor" value={yaml} onChange={(e) => setYaml(e.target.value)} spellCheck={false} />
            <LegBuilder yaml={yaml} onChange={setYaml} />
            <div className="cluster">
              <input aria-label="change note" placeholder="change note" value={changeNote} onChange={(e) => setChangeNote(e.target.value)} />
              <Button variant="primary" onClick={save} disabled={!report?.valid || (!!strategyId && !changeNote)}>{strategyId ? 'New version' : 'Create strategy'}</Button>
              {strategyId && <Button onClick={() => setCloning(true)}>Clone</Button>}
              {strategyId && <Link to={`/strategies/${strategyId}`}>Strategy page</Link>}
            </div>
            {message && <p data-testid="lab-message" className="message">{message}</p>}
          </div>
        </Card>
        <div className="stack">
          <Card title="Validation">
            {report?.valid && <p className="tone-profit">✓ valid</p>}
            {report && !report.valid && <ul className="tone-loss">{report.errors.map((e) => <li key={e.path + e.message}>✗ {e.path}: {e.message}</li>)}</ul>}
            {!report && <p className="muted">Checking…</p>}
          </Card>
          {latest && (
            <>
              <Card title={<>Backtest v{latest.version}</>}>
                <div className="stack">
                  <Field label="From"><input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
                  <Field label="To"><input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
                  <Field label="Instrument (optional)"><input value={instrument} onChange={(e) => setInstrument(e.target.value)} placeholder="NFO:NIFTY:FUT:CONT" /></Field>
                  <Field label="Splits"><select value={splits} onChange={(e) => setSplits(e.target.value)}><option>FIXED</option><option>WALK_FORWARD</option><option>NONE</option></select></Field>
                  <Button variant="primary" onClick={runBacktest}>Run backtest</Button>
                  <ul className="plain-list text-sm">
                    {(backtests ?? []).map((b) => (
                      <li key={b.id}>{b.status} {b.progressPct}% {b.metrics ? `— ${b.metrics.totalTrades} trades, ${b.metrics.expectancyR.toFixed(2)}R` : ''} {b.error ?? ''}</li>
                    ))}
                  </ul>
                </div>
              </Card>
              <Card title="Experiments">
                <Experiments versionId={latest.id} />
              </Card>
            </>
          )}
        </div>
      </div>
      <Dialog
        open={cloning}
        title="Clone this strategy"
        onClose={() => setCloning(false)}
        actions={<>
          <Button onClick={() => setCloning(false)}>Cancel</Button>
          <Button variant="primary" disabled={!cloneName.trim()} onClick={clone}>Clone</Button>
        </>}
      >
        <Field label="New strategy name (slug)"><input value={cloneName} onChange={(e) => setCloneName(e.target.value)} /></Field>
      </Dialog>
    </Page>
  );
}
