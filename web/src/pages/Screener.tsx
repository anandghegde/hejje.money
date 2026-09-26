import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ColumnDef, SortingState, flexRender, getCoreRowModel, getSortedRowModel, useReactTable } from '@tanstack/react-table';
import { ApiError, request } from '../api/client';
import { RatingsList, SavedScreen, ScreenFilter, ScreenResult, ScreenRow } from '../api/types';
import { LISTS, LIST_LABEL, OPS, buildFilter, conditionColor, describeFilter, directionColor, rateWithCount, signedPct, surveillanceLabel } from '../lib/context';

interface Regime { marketCondition?: string; evidence?: string[] }

/** The daily market call with the sentence behind it. Display only until the validation passes. */
export function MarketConditionBanner() {
  const { data } = useQuery({ queryKey: ['regime'], queryFn: () => request<Regime>('/context/regime'), refetchInterval: 300_000 });
  const condition = data?.marketCondition ?? 'UNKNOWN';
  const sentence = data?.evidence?.find((e) => e.startsWith('Market condition'));
  return (
    <div data-testid="market-condition" style={{ padding: 10, borderRadius: 6, background: '#f4f6f8', borderLeft: `6px solid ${conditionColor(condition)}` }}>
      <b style={{ color: conditionColor(condition) }}>{condition.replace(/_/g, ' ')}</b>
      {sentence ? <span style={{ marginLeft: 12, color: '#424242' }}>{sentence.replace(/^Market condition [A-Z_]+: /, '')}</span> : null}
    </div>
  );
}

export function Disabled({ what, error }: { what: string; error: unknown }) {
  const off = error instanceof ApiError && error.status === 503;
  return <p data-testid="context-disabled">{off ? `${what} are switched off on this server (${(error as Error).message}).` : `${what} unavailable: ${(error as Error).message}`}</p>;
}

/** NSE's ASM/GSM measure as a badge; nothing when the stock is not under surveillance. Display only: it changes no score. */
export function SurveillanceBadge({ flag, code, asOf, stale }: { flag?: string | null; code?: string | null; asOf?: string; stale?: boolean }) {
  const label = surveillanceLabel(flag);
  if (!label) return null;
  const title = `NSE ${code ?? flag}${asOf ? `, lists of ${asOf}` : ''}${stale ? ' (stale: not refreshed for this session)' : ''}. Display only.`;
  return (
    <span data-testid="surveillance-badge" title={title} style={{ fontSize: 12, fontWeight: 700, color: '#fff', borderRadius: 4, padding: '1px 6px',
      background: label.startsWith('GSM') ? '#c0392b' : '#b7791f', opacity: stale ? 0.6 : 1 }}>{label}{stale ? ' (stale)' : ''}</span>
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

const COLUMNS: ColumnDef<ScreenRow>[] = [
  { accessorKey: 'symbol', header: 'Symbol', cell: (c) => <Link to={`/stocks/${encodeURIComponent(String(c.getValue()))}`}>{String(c.getValue())}</Link> },
  { accessorKey: 'surveillance', header: 'Surv.', cell: (c) => <SurveillanceBadge flag={c.getValue() as string | null} /> },
  { accessorKey: 'close', header: 'Close' },
  { accessorKey: 'changePct', header: 'Chg', cell: (c) => signedPct(c.getValue() as number | null, 1) },
  { accessorKey: 'rsRating', header: 'RS' },
  { accessorKey: 'adGrade', header: 'A/D' },
  { accessorKey: 'techComposite', header: 'Tech comp.' },
  { accessorKey: 'offHighPct', header: 'Off high', cell: (c) => (c.getValue() == null ? '—' : `-${(c.getValue() as number).toFixed(1)} %`) },
  { accessorKey: 'volVsAvg50Pct', header: 'Vol vs 50d', cell: (c) => signedPct(c.getValue() as number | null, 0) },
  { accessorKey: 'groupRank', header: 'Group rank' },
  { id: 'setup', header: 'Setup', accessorFn: (r) => (r.baseType ? `${r.baseType} ${r.baseStatus}${r.volumeConfirmed ? ' (volume)' : ''}` : '') },
  { accessorKey: 'pivot', header: 'Pivot' },
  { id: 'analog', header: 'Analogs (15d → 5d)', accessorFn: (r) => (r.analogWinRate5 as number | undefined) ?? -1,
    cell: (c) => {
      const r = c.row.original;
      if (r.analogCount5 == null) return '—';
      return <span style={{ color: directionColor(r.analogDirection as string) }}>{String(r.analogDirection ?? '')} · {rateWithCount(r.analogWinRate5 as number, r.analogCount5 as number)}</span>;
    } },
];

function RowsTable({ rows }: { rows: ScreenRow[] }) {
  const [sorting, setSorting] = useState<SortingState>([]);
  const table = useReactTable({ data: rows, columns: COLUMNS, state: { sorting }, onSortingChange: setSorting, getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel() });
  if (rows.length === 0) return <p>Nothing matches.</p>;
  return (
    <table data-testid="screener-table">
      <thead>
        {table.getHeaderGroups().map((g) => (
          <tr key={g.id}>{g.headers.map((h) => (
            <th key={h.id} onClick={h.column.getToggleSortingHandler()} style={{ cursor: 'pointer', textAlign: 'left', paddingRight: 12 }}>
              {flexRender(h.column.columnDef.header, h.getContext())}{{ asc: ' ▲', desc: ' ▼' }[h.column.getIsSorted() as string] ?? ''}
            </th>))}
          </tr>))}
      </thead>
      <tbody>
        {table.getRowModel().rows.map((row) => (
          <tr key={row.id}>{row.getVisibleCells().map((cell) => <td key={cell.id} style={{ paddingRight: 12 }}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>)}</tr>
        ))}
      </tbody>
    </table>
  );
}

function Groups({ list }: { list: RatingsList }) {
  return (
    <table data-testid="groups-table">
      <thead><tr><th>Rank</th><th style={{ textAlign: 'left' }}>Group</th><th>Strength (median RS raw)</th><th>Members</th></tr></thead>
      <tbody>{(list.groups ?? []).map((g) => <tr key={g.groupId}><td>{g.rank}</td><td>{g.name}</td><td>{signedPct(g.strength * 100, 1)}</td><td>{g.members}</td></tr>)}</tbody>
    </table>
  );
}

function ListTab({ name }: { name: string }) {
  const { data, error } = useQuery({ queryKey: ['ratings-list', name], queryFn: () => request<RatingsList>(`/ratings/lists/${name}`) });
  const rows = useMemo(() => (data ? toRows(data) : []), [data]);
  if (error) return <Disabled what="Daily ratings" error={error} />;
  if (!data) return <p>Loading…</p>;
  return (
    <div>
      <p style={{ color: '#616161' }}>Session {data.date || '—'}{name === 'setups' ? ' · in the buy zone, then triggered, then near the pivot; each by technical composite' : ''}</p>
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
    <div>
      <div data-testid="saved-screens" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
        {(saved ?? []).map((s) => (
          <span key={s.id} style={{ border: '1px solid #cfd8dc', borderRadius: 12, padding: '2px 8px' }}>
            <a href="#" onClick={(e) => { e.preventDefault(); setFilters(s.definition.filters ?? []); setSort(s.definition.sort ?? '-techComposite'); }}>{s.name}</a>
            {!s.seeded && <button aria-label={`delete ${s.name}`} onClick={() => remove.mutate(s.id)} style={{ marginLeft: 6 }}>×</button>}
          </span>))}
      </div>
      <div data-testid="filter-builder" style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        {filters.map((f, i) => (
          <span key={i} style={{ background: '#e3f2fd', borderRadius: 4, padding: '2px 6px' }}>
            {describeFilter(f)} <button aria-label={`remove filter ${i}`} onClick={() => setFilters(filters.filter((_, k) => k !== i))}>×</button>
          </span>))}
        <select aria-label="field" value={field} onChange={(e) => setField(e.target.value)}>{(fields ?? [field]).map((f) => <option key={f}>{f}</option>)}</select>
        <select aria-label="operator" value={op} onChange={(e) => setOp(e.target.value as ScreenFilter['op'])}>{OPS.map((o) => <option key={o}>{o}</option>)}</select>
        <input aria-label="value" value={value} onChange={(e) => setValue(e.target.value)} placeholder="value (a,b for in)" />
        <button onClick={add}>Add filter</button>
        <label>sort <select aria-label="sort" value={sort} onChange={(e) => setSort(e.target.value)}>
          {(fields ?? ['techComposite']).flatMap((f) => [`-${f}`, f]).map((f) => <option key={f}>{f}</option>)}</select></label>
        <button data-testid="run-screen" onClick={() => run.mutate()}>Run</button>
        <input aria-label="screen name" value={name} onChange={(e) => setName(e.target.value)} placeholder="save as…" />
        <button disabled={!name.trim()} onClick={() => save.mutate()}>Save</button>
      </div>
      {run.error ? <Disabled what="Daily ratings" error={run.error} /> : null}
      {save.error ? <p style={{ color: '#c0392b' }}>{(save.error as Error).message}</p> : null}
      {run.data && (
        <div>
          <p data-testid="screen-summary" style={{ color: '#616161' }}>Session {run.data.date || '—'} · {run.data.matched} of {run.data.universe} stocks match</p>
          <RowsTable rows={run.data.rows} />
        </div>)}
    </div>
  );
}

export function Screener() {
  const [tab, setTab] = useState<string>('setups');
  return (
    <div>
      <h2>Screener</h2>
      <MarketConditionBanner />
      <p style={{ color: '#616161', fontSize: 13 }}>Daily context over the NIFTY 500 (D1). Evidence only: not validated, so it changes no
        score or decision (protocol: docs/strategies/context-validation.md). Technical ratings only, no fundamentals.</p>
      <div data-testid="screener-tabs" style={{ display: 'flex', gap: 4, margin: '12px 0' }}>
        {[...LISTS, 'screen'].map((t) => (
          <button key={t} onClick={() => setTab(t)} style={{ fontWeight: tab === t ? 700 : 400, borderBottom: tab === t ? '2px solid #1565c0' : '2px solid transparent' }}>
            {t === 'screen' ? 'Custom screen' : LIST_LABEL[t]}
          </button>))}
      </div>
      {tab === 'screen' ? <ScreenTab /> : <ListTab name={tab} />}
    </div>
  );
}
