import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ColumnDef } from '@tanstack/react-table';
import { request } from '../api/client';
import { Position } from '../api/types';
import { formatPaise } from '../lib/sizing';
import { Badge, Button, DataTable, Dialog, EmptyState, Page, PHONE, signed, tone, useMediaQuery } from '../ui';

/** Realized P&L in rupees, signed and toned (the profit/loss convention). */
function Pnl({ paise }: { paise: number }) {
  return <span className={`tone-${tone(paise)}`}>{signed(paise / 100)}</span>;
}

export function Positions() {
  const qc = useQueryClient();
  const phone = useMediaQuery(PHONE);
  const [confirmCloseAll, setConfirmCloseAll] = useState(false);
  const { data, isLoading } = useQuery({ queryKey: ['positions'], queryFn: () => request<Position[]>('/positions'), refetchInterval: 3000 });

  async function close(p: Position) {
    await request('/positions/close', { method: 'POST', idempotent: true, body: { instrumentId: p.instrumentId, product: p.product } });
    qc.invalidateQueries({ queryKey: ['positions'] });
  }
  async function closeAll() {
    setConfirmCloseAll(false);
    await request('/positions/close-all', { method: 'POST', idempotent: true });
    qc.invalidateQueries({ queryKey: ['positions'] });
  }

  const columns: ColumnDef<Position, any>[] = [
    { id: 'instrument', header: 'Instrument', accessorFn: (p) => p.instrumentId.slice(0, 8), cell: (c) => <span className="mono">{c.getValue()}</span> },
    { accessorKey: 'product', header: 'Product' },
    { accessorKey: 'netQuantity', header: 'Net', meta: { numeric: true },
      cell: (c) => <span data-testid={`pos-net-${c.row.original.instrumentId}`}>{c.getValue()}</span> },
    { accessorKey: 'averagePrice', header: 'Avg', meta: { numeric: true } },
    { id: 'realized', header: 'Realized', accessorFn: (p) => p.realizedPnl.paise, meta: { numeric: true }, cell: (c) => <Pnl paise={c.getValue()} /> },
    { id: 'fees', header: 'Fees', accessorFn: (p) => p.fees.paise, meta: { numeric: true }, cell: (c) => formatPaise(c.getValue()) },
    { id: 'close', header: '', enableSorting: false,
      cell: (c) => c.row.original.netQuantity !== 0 && <Button size="sm" onClick={() => close(c.row.original)}>Close</Button> },
  ];

  return (
    <Page title="Positions" actions={<Button variant="danger" onClick={() => setConfirmCloseAll(true)}>Close all</Button>}>
      {phone ? (
        (data ?? []).length === 0 ? <EmptyState title={isLoading ? 'Loading positions…' : 'No positions'} /> : (
          <div className="item-cards" data-testid="positions-cards">
            {(data ?? []).map((p) => (
              <div key={p.id} className="item-card">
                <div className="item-card-head">
                  <span className="mono">{p.instrumentId.slice(0, 8)}</span>
                  <Badge>{p.product}</Badge>
                </div>
                <dl className="kv">
                  <dt>Net</dt><dd className="num">{p.netQuantity}</dd>
                  <dt>Avg</dt><dd className="num">{p.averagePrice}</dd>
                  <dt>Realized</dt><dd className="num"><Pnl paise={p.realizedPnl.paise} /></dd>
                  <dt>Fees</dt><dd className="num">{formatPaise(p.fees.paise)}</dd>
                </dl>
                {p.netQuantity !== 0 && <Button onClick={() => close(p)}>Close</Button>}
              </div>
            ))}
          </div>
        )
      ) : (
        <DataTable data-testid="positions-table" columns={columns} data={data} loading={isLoading} empty="No positions" getRowId={(p) => p.id} />
      )}
      <Dialog
        open={confirmCloseAll}
        title="Close all positions?"
        onClose={() => setConfirmCloseAll(false)}
        actions={<>
          <Button onClick={() => setConfirmCloseAll(false)}>Keep them</Button>
          <Button variant="danger" onClick={closeAll}>Close all</Button>
        </>}
      >
        Every open position is closed at market.
      </Dialog>
    </Page>
  );
}
