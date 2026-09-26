import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ColumnDef } from '@tanstack/react-table';
import { request } from '../api/client';
import { formatPaise } from '../lib/sizing';
import { SwingBookRow, SwingRisk, SwingSetup, gttLabel, gttTone, price, riskUse, setupLabel, setupTone } from '../lib/swing';
import { Badge, Button, Card, DataTable, Dialog, EmptyState, Page, PHONE, Stat, signed, tone, toNumber, useMediaQuery } from '../ui';
import '../styles/trading.css';

const rupees = (paise: number) => `₹${formatPaise(paise)}`;

function Pnl({ paise }: { paise: number }) {
  return <span className={`tone-${tone(paise)}`}>{signed(paise / 100)}</span>;
}

function R({ value }: { value: unknown }) {
  const r = toNumber(value);
  return <span className={`tone-${tone(r)}`}>{signed(r, 2, ' R')}</span>;
}

/** Plan M11.6: the swing book (delivery positions held overnight with their broker-side GTT stops), today's setups, overnight risk. */
export function Swing() {
  const qc = useQueryClient();
  const phone = useMediaQuery(PHONE);
  const [closing, setClosing] = useState<SwingBookRow | null>(null);
  const book = useQuery({ queryKey: ['swing-positions'], queryFn: () => request<SwingBookRow[]>('/swing/positions'), refetchInterval: 5000 });
  const risk = useQuery({ queryKey: ['swing-risk'], queryFn: () => request<SwingRisk>('/swing/risk'), refetchInterval: 5000 });
  const setups = useQuery({ queryKey: ['swing-setups'], queryFn: () => request<SwingSetup[]>('/swing/setups'), refetchInterval: 15000 });

  async function close(row: SwingBookRow) {
    setClosing(null);
    await request('/swing/positions/close', { method: 'POST', idempotent: true, body: { symbol: row.symbol } });
    qc.invalidateQueries({ queryKey: ['swing-positions'] });
    qc.invalidateQueries({ queryKey: ['swing-risk'] });
  }

  const r = risk.data;
  const use = r ? riskUse(r.overnightRisk.paise, r.budget.paise) : null;

  const columns: ColumnDef<SwingBookRow, any>[] = [
    { accessorKey: 'symbol', header: 'Symbol', cell: (c) => <span className="mono">{c.getValue()}</span> },
    { accessorKey: 'entryDate', header: 'Entry date' },
    { accessorKey: 'daysHeld', header: 'Days', meta: { numeric: true } },
    { accessorKey: 'quantity', header: 'Qty', meta: { numeric: true } },
    { id: 'entry', header: 'Entry', accessorFn: (p) => toNumber(p.entryPrice), meta: { numeric: true }, cell: (c) => price(c.getValue()) },
    { id: 'last', header: 'Last', accessorFn: (p) => toNumber(p.lastPrice), meta: { numeric: true }, cell: (c) => price(c.getValue()) },
    { id: 'stop', header: 'Stop', accessorFn: (p) => toNumber(p.stop), meta: { numeric: true }, cell: (c) => price(c.getValue()) },
    { id: 'goal', header: 'Goal', accessorFn: (p) => toNumber(p.goal), meta: { numeric: true }, cell: (c) => price(c.getValue()) },
    { id: 'r', header: 'R', accessorFn: (p) => toNumber(p.r), meta: { numeric: true }, cell: (c) => <R value={c.getValue()} /> },
    { id: 'unrealized', header: 'Unrealized', accessorFn: (p) => p.unrealizedPnl.paise, meta: { numeric: true }, cell: (c) => <Pnl paise={c.getValue()} /> },
    { accessorKey: 'gtt', header: 'GTT', cell: (c) => <Badge tone={gttTone(c.getValue())} data-testid={`swing-gtt-${c.row.original.symbol}`}>{gttLabel(c.getValue())}</Badge> },
    { id: 'close', header: '', enableSorting: false, cell: (c) => <Button size="sm" onClick={() => setClosing(c.row.original)}>Exit</Button> },
  ];

  const setupColumns: ColumnDef<SwingSetup, any>[] = [
    { accessorKey: 'symbol', header: 'Symbol', cell: (c) => <span className="mono">{c.getValue()}</span> },
    { accessorKey: 'type', header: 'Setup' },
    { id: 'pivot', header: 'Pivot', accessorFn: (s) => toNumber(s.pivot), meta: { numeric: true }, cell: (c) => price(c.getValue()) },
    { id: 'buyHigh', header: 'Buy to', accessorFn: (s) => toNumber(s.buyHigh), meta: { numeric: true }, cell: (c) => price(c.getValue()) },
    { id: 'stop', header: 'Stop', accessorFn: (s) => toNumber(s.stop), meta: { numeric: true }, cell: (c) => price(c.getValue()) },
    { id: 'goal', header: 'Goal', accessorFn: (s) => toNumber(s.goal), meta: { numeric: true }, cell: (c) => price(c.getValue()) },
    { id: 'last', header: 'Last', accessorFn: (s) => toNumber(s.lastClose), meta: { numeric: true }, cell: (c) => price(c.getValue()) },
    { id: 'pace', header: 'Volume pace', accessorFn: (s) => toNumber(s.pace), meta: { numeric: true },
      cell: (c) => (c.getValue() === null ? '—' : `${(c.getValue() as number).toFixed(2)}×`) },
    { accessorKey: 'state', header: 'State', cell: (c) => <Badge tone={setupTone(c.getValue())}>{setupLabel(c.getValue())}</Badge> },
  ];

  return (
    <Page title="Swing" actions={<Badge tone="paper">PAPER only</Badge>}>
      <Card title="Overnight risk" data-testid="swing-risk">
        {r ? (
          <div className="stat-grid">
            <Stat label="Overnight risk (gap-adjusted)" data-testid="swing-overnight-risk" value={rupees(r.overnightRisk.paise)} />
            <Stat label="Budget" value={rupees(r.budget.paise)} />
            <Stat label="Budget used" value={use?.pct == null ? '—' : <Badge tone={use.tone}>{`${use.pct}%`}</Badge>} />
            <Stat label="Positions" data-testid="swing-open" value={`${r.openPositions}/${r.maxOpenPositions}`} />
            <Stat label="Deployed" value={`${rupees(r.deployed.paise)} of ${rupees(r.capital.paise)}`} />
            <Stat label="Gap allowance" value={`${toNumber(r.gapAllowancePct)}%`} />
          </div>
        ) : <EmptyState title={risk.isLoading ? 'Loading…' : 'Overnight risk unavailable'} />}
      </Card>

      <Card title="Positions">
        {phone ? (
          (book.data ?? []).length === 0 ? <EmptyState title={book.isLoading ? 'Loading positions…' : 'No swing positions'} /> : (
            <div className="item-cards" data-testid="swing-cards">
              {(book.data ?? []).map((p) => (
                <div key={p.id} className="item-card">
                  <div className="item-card-head">
                    <span className="mono">{p.symbol}</span>
                    <Badge tone={gttTone(p.gtt)}>{gttLabel(p.gtt)}</Badge>
                  </div>
                  <dl className="kv">
                    <dt>Held</dt><dd className="num">{p.daysHeld} sessions since {p.entryDate}</dd>
                    <dt>Qty × entry</dt><dd className="num">{p.quantity} × {price(p.entryPrice)}</dd>
                    <dt>Last</dt><dd className="num">{price(p.lastPrice)}</dd>
                    <dt>Stop / goal</dt><dd className="num">{price(p.stop)} / {price(p.goal)}</dd>
                    <dt>R</dt><dd className="num"><R value={p.r} /></dd>
                    <dt>Unrealized</dt><dd className="num"><Pnl paise={p.unrealizedPnl.paise} /></dd>
                  </dl>
                  <Button onClick={() => setClosing(p)}>Exit</Button>
                </div>
              ))}
            </div>
          )
        ) : (
          <DataTable data-testid="swing-table" columns={columns} data={book.data} loading={book.isLoading} empty="No swing positions" getRowId={(p) => p.id} />
        )}
      </Card>

      <Card title="Setups watched today">
        <DataTable data-testid="swing-setups" columns={setupColumns} data={setups.data} loading={setups.isLoading}
          empty="No READY setups on a swing deployment today" getRowId={(s) => `${s.deploymentId}-${s.baseId}`} />
      </Card>

      <Dialog
        open={closing !== null}
        title={closing ? `Exit ${closing.symbol}?` : 'Exit?'}
        onClose={() => setClosing(null)}
        actions={<>
          <Button onClick={() => setClosing(null)}>Keep it</Button>
          <Button variant="danger" onClick={() => closing && void close(closing)}>Exit at market</Button>
        </>}
      >
        The whole delivery position is sold at market and its GTT at the broker is cancelled in the same operation.
      </Dialog>
    </Page>
  );
}
