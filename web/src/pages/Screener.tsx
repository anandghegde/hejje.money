import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ColumnDef } from '@tanstack/react-table';
import { ApiError, request } from '../api/client';
import { RatingsList, SavedScreen, ScreenFilter, ScreenResult, ScreenRow } from '../api/types';
import { Badge, Button, Card, DataTable, Field, Page } from '../ui';
import '../styles/research.css';
import { LISTS, LIST_LABEL, OPS, buildFilter, conditionTone, describeFilter, directionTone, rateWithCount, signedPct, surveillanceLabel } from '../lib/context';

interface Regime { marketCondition?: string; evidence?: string[] }

/** The daily market call with the sentence behind it. Display only until the validation passes. */
export function MarketConditionBanner() {
  const { data } = useQuery({ queryKey: ['regime'], queryFn: () => request<Regime>('/context/regime'), refetchInterval: 300_000 });
  const condition = data?.marketCondition ?? 'UNKNOWN';
  const sentence = data?.evidence?.find((e) => e.startsWith('Market condition'));
  return (
    <div data-testid="market-condition" className={`market-condition market-condition-${conditionTone(condition)}`}>
      <Badge tone={conditionTone(condition)}>{condition.replace(/_/g, ' ')}</Badge>
      {sentence ? <span>{sentence.replace(/^Market condition [A-Z_]+: /, '')}</span> : null}
    </div>
  );
}

export function Disabled({ what, error }: { what: string; error: unknown }) {
  const off = error instanceof ApiError && error.status === 503;
  return <p data-testid="context-disabled" className="message">{off ? `${what} are switched off on this server (${(error as Error).message}).` : `${what} unavailable: ${(error as Error).message}`}</p>;
}

/** NSE's ASM/GSM measure as a badge; nothing when the stock is not under surveillance. Display only: it changes no score. */
export function SurveillanceBadge({ flag, code, asOf, stale }: { flag?: string | null; code?: string | null; asOf?: string; stale?: boolean }) {
  const label = surveillanceLabel(flag);
  if (!label) return null;
  const title = `NSE ${code ?? flag}${asOf ? `, lists of ${asOf}` : ''}${stale ? ' (stale: not refreshed for this session)' : ''}. Display only.`;
  return (
    <Badge data-testid="surveillance-badge" title={title} tone={label.startsWith('GSM') ? 'loss' : 'warning'} className={stale ? 'badge-stale' : undefined}>
      {label}{stale ? ' (stale)' : ''}</Badge>
  );
}

/** One flat row per stock: a list row and a screen row share the columns. */
function toRows(list: RatingsList): ScreenRow[] {
  return (list.rows ?? []).map((r) => ({
    symbol: r.rating?.symbol ?? r.base?.symbol ?? '', close: r.rating ? Number(r.rating.close) : null, changePct: r.rating?.changePct ?? null,
    rsRating: r.rating?.rsRating ?? null, adGrade: r.rating?.adGrade ?? null, techComposite: r.rating?.techComposite ?? null,
    offHighPct: r.rating?.offHighPct ?? null, volVsAvg50Pct: r.rating?.volVsAvg50Pct ?? null, groupRank: r.rating?.groupRank ?? null,
    baseType: r.base?.type ?? null, baseStatus: r.base?.status ?? null, pivot: r.base ? Number(r.base.pivot) : null,
    volumeConfirmed: r.base?.volumeConfirmed ?? null, surveillance: r.rating?.surveillance?.flag ?? null,
  }));
}

const pct = (v: number | null) => <span className={`tone-${v == null || v === 0 ? 'neutral' : v > 0 ? 'profit' : 'loss'}`}>{signedPct(v, 1)}</span>;

const COLUMNS: ColumnDef<ScreenRow, any>[] = [
  { accessorKey: 'symbol', header: 'Symbol', cell: (c) => <Link to={`/stocks/${encodeURIComponent(String(c.getValue()))}`}>{String(c.getValue())}</Link> },
  { accessorKey: 'surveillance', header: 'Surv.', cell: (c) => <SurveillanceBadge flag={c.getValue() as string | null} /> },
  { accessorKey: 'close', header: 'Close', meta: { numeric: true } },
  { accessorKey: 'changePct', header: 'Chg', meta: { numeric: true }, cell: (c) => pct(c.getValue() as number | null) },
  { accessorKey: 'rsRating', header: 'RS', meta: { numeric: true } },
  { accessorKey: 'adGrade', header: 'A/D' },
  { accessorKey: 'techComposite', header: 'Tech comp.', meta: { numeric: true } },
  { accessorKey: 'offHighPct', header: 'Off high', meta: { numeric: true }, cell: (c) => (c.getValue() == null ? '—' : `-${(c.getValue() as number).toFixed(1)} %`) },
  { accessorKey: 'volVsAvg50Pct', header: 'Vol vs 50d', meta: { numeric: true }, cell: (c) => signedPct(c.getValue() as number | null, 0) },
  { accessorKey: 'groupRank', header: 'Group rank', meta: { numeric: true } },
  { id: 'setup', header: 'Setup', accessorFn: (r) => (r.baseType ? `${r.baseType} ${r.baseStatus}${r.volumeConfirmed ? ' (volume)' : ''}` : '') },
  { accessorKey: 'pivot', header: 'Pivot', meta: { numeric: true } },
  { id: 'analog', header: 'Analogs (15d → 5d)', accessorFn: (r) => (r.analogWinRate5 as number | undefined) ?? -1,
    cell: (c) => {
      const r = c.row.original;
      if (r.analogCount5 == null) return '—';
      return <span className={`tone-${directionTone(r.analogDirection as string)}`}>{String(r.analogDirection ?? '')} · {rateWithCount(r.analogWinRate5 as number, r.analogCount5 as number)}</span>;
    } },
];

function RowsTable({ rows }: { rows: ScreenRow[] }) {
  return <DataTable data-testid="screener-table" columns={COLUMNS} data={rows} empty="Nothing matches." />;
}

function Groups({ list }: { list: RatingsList }) {
  return (
    <div className="table-scroll">
      <table data-testid="groups-table">
        <thead><tr><th className="num">Rank</th><th>Group</th><th className="num">Strength (median RS raw)</th><th className="num">Members</th></tr></thead>
        <tbody>{(list.groups ?? []).map((g) => <tr key={g.groupId}><td className="num">{g.rank}</td><td>{g.name}</td><td className="num">{pct(g.strength * 100)}</td><td className="num">{g.members}</td></tr>)}</tbody>
      </table>
    </div>
  );
}

function ListTab({ name }: { name: string }) {
  const { data, error } = useQuery({ queryKey: ['ratings-list', name], queryFn: () => request<RatingsList>(`/ratings/lists/${name}`) });
  const rows = useMemo(() => (data ? toRows(data) : []), [data]);
  if (error) return <Disabled what="Daily ratings" error={error} />;
  if (!data) return <p>Loading…</p>;
  return (
    <div className="stack">
      <p className="muted">Session {data.date || '—'}{name === 'setups' ? ' · in the buy zone, then triggered, then near the pivot; each by technical composite' : ''}</p>
      {name === 'groups' ? <Groups list={data} /> : <RowsTable rows={rows} />}
    </div>
  );
}

function ScreenTab() {
  const client = useQueryClient();
  const [filters, setFilters] = useState<ScreenFilter[]>([{ field: 'rsRating', op: 'gte', value: 80 }]);
  const [field, setField] = useState('techComposite');
  const [op, setOp] = useState<ScreenFilter['op']>('gte');
  const [value, setValue] = useState('');
  const [sort, setSort] = useState('-techComposite');
  const [name, setName] = useState('');
  const { data: fields } = useQuery({ queryKey: ['screen-fields'], queryFn: () => request<string[]>('/ratings/screen/fields') });
  const { data: saved } = useQuery({ queryKey: ['screens'], queryFn: () => request<SavedScreen[]>('/ratings/screens') });
  const run = useMutation({ mutationFn: () => request<ScreenResult>('/ratings/screen', { method: 'POST', body: { filters, sort, limit: 200 } }) });
  const save = useMutation({ mutationFn: () => request<SavedScreen>('/ratings/screens', { method: 'POST', body: { name, definition: { filters, sort, limit: 200 } } }),
    onSuccess: () => { setName(''); client.invalidateQueries({ queryKey: ['screens'] }); } });
  const remove = useMutation({ mutationFn: (id: string) => request(`/ratings/screens/${id}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['screens'] }) });

  function add() {
    const f = buildFilter(field, op, value);
    if (f) { setFilters([...filters, f]); setValue(''); }
  }

  return (
    <div className="stack">
      <div data-testid="saved-screens" className="chips">
        {(saved ?? []).map((s) => (
          <span key={s.id} className="chip">
            <a href="#" onClick={(e) => { e.preventDefault(); setFilters(s.definition.filters ?? []); setSort(s.definition.sort ?? '-techComposite'); }}>{s.name}</a>
            {!s.seeded && <button aria-label={`delete ${s.name}`} onClick={() => remove.mutate(s.id)}>×</button>}
          </span>))}
      </div>
      <div className="chips">
        {filters.map((f, i) => (
          <span key={i} className="chip chip-accent">
            {describeFilter(f)} <button aria-label={`remove filter ${i}`} onClick={() => setFilters(filters.filter((_, k) => k !== i))}>×</button>
          </span>))}
      </div>
      <div data-testid="filter-builder" className="filter-builder">
        <Field label="Field"><select aria-label="field" value={field} onChange={(e) => setField(e.target.value)}>{(fields ?? [field]).map((f) => <option key={f}>{f}</option>)}</select></Field>
        <Field label="Operator"><select aria-label="operator" value={op} onChange={(e) => setOp(e.target.value as ScreenFilter['op'])}>{OPS.map((o) => <option key={o}>{o}</option>)}</select></Field>
        <Field label="Value"><input aria-label="value" value={value} onChange={(e) => setValue(e.target.value)} placeholder="value (a,b for in)" /></Field>
        <Button onClick={add}>Add filter</Button>
        <Field label="Sort"><select aria-label="sort" value={sort} onChange={(e) => setSort(e.target.value)}>
          {(fields ?? ['techComposite']).flatMap((f) => [`-${f}`, f]).map((f) => <option key={f}>{f}</option>)}</select></Field>
        <Button variant="primary" data-testid="run-screen" onClick={() => run.mutate()}>Run</Button>
        <Field label="Save as"><input aria-label="screen name" value={name} onChange={(e) => setName(e.target.value)} placeholder="save as…" /></Field>
        <Button disabled={!name.trim()} onClick={() => save.mutate()}>Save</Button>
      </div>
      {run.error ? <Disabled what="Daily ratings" error={run.error} /> : null}
      {save.error ? <p className="message message-loss">{(save.error as Error).message}</p> : null}
      {run.data && (
        <div className="stack">
          <p data-testid="screen-summary" className="muted">Session {run.data.date || '—'} · {run.data.matched} of {run.data.universe} stocks match</p>
          <RowsTable rows={run.data.rows} />
        </div>)}
    </div>
  );
}

export function Screener() {
  const [tab, setTab] = useState<string>('setups');
  return (
    <Page title="Screener">
      <MarketConditionBanner />
      <p className="muted text-sm">Daily context over the NIFTY 500 (D1). Evidence only: not validated, so it changes no
        score or decision (protocol: docs/strategies/context-validation.md). Technical ratings only, no fundamentals.</p>
      <Card>
        {/* buttons, not a tablist: each loads a different list */}
        <div data-testid="screener-tabs" className="tabs">
          {[...LISTS, 'screen'].map((t) => (
            <button key={t} type="button" className="tab" aria-pressed={tab === t} onClick={() => setTab(t)}>
              {t === 'screen' ? 'Custom screen' : LIST_LABEL[t]}
            </button>))}
        </div>
        <div className="section">{tab === 'screen' ? <ScreenTab /> : <ListTab name={tab} />}</div>
      </Card>
    </Page>
  );
}
