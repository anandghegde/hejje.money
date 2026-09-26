import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ColumnDef } from '@tanstack/react-table';
import { request } from '../api/client';
import { Order } from '../api/types';
import { ManualOrder } from './ManualOrder';
import { Baskets, Splits } from '../components/Baskets';
import { Badge, BadgeTone, Button, Card, DataTable, Page } from '../ui';

const CANCELLABLE = ['OPEN', 'PARTIALLY_FILLED', 'BROKER_ACCEPTED'];

function orderStateTone(state: string): BadgeTone {
  if (state === 'FILLED') return 'profit';
  if (state === 'REJECTED' || state === 'FAILED') return 'loss';
  if (CANCELLABLE.includes(state)) return 'info';
  return 'neutral';
}

export function Orders() {
  const qc = useQueryClient();
  const { data: orders, isLoading } = useQuery({ queryKey: ['orders'], queryFn: () => request<Order[]>('/orders'), refetchInterval: 3000 });

  async function cancel(id: string) {
    await request(`/orders/${id}/cancel`, { method: 'POST', idempotent: true });
    qc.invalidateQueries({ queryKey: ['orders'] });
  }

  const columns: ColumnDef<Order, any>[] = [
    { id: 'time', header: 'Time', accessorFn: (o) => o.updatedAt, cell: (c) => <span className="num">{new Date(c.getValue()).toLocaleTimeString()}</span> },
    { accessorKey: 'side', header: 'Side' },
    { accessorKey: 'quantity', header: 'Qty', meta: { numeric: true } },
    { accessorKey: 'filledQuantity', header: 'Filled', meta: { numeric: true } },
    { accessorKey: 'orderType', header: 'Type' },
    { accessorKey: 'averagePrice', header: 'Avg', meta: { numeric: true } },
    { accessorKey: 'state', header: 'State',
      cell: (c) => <Badge tone={orderStateTone(c.getValue())} data-testid={`order-state-${c.row.original.id}`}>{c.getValue()}</Badge> },
    { id: 'broker', header: 'Broker ID', accessorFn: (o) => o.brokerOrderId ?? '—', cell: (c) => <span className="mono">{c.getValue()}</span> },
    { id: 'cancel', header: '', enableSorting: false,
      cell: (c) => CANCELLABLE.includes(c.row.original.state) && <Button size="sm" onClick={() => cancel(c.row.original.id)}>Cancel</Button> },
  ];

  return (
    <Page title="Orders">
      <ManualOrder onPlaced={() => qc.invalidateQueries({ queryKey: ['orders'] })} />
      <Card title="Order book">
        <DataTable data-testid="orders-table" columns={columns} data={orders} loading={isLoading} empty="No orders yet" getRowId={(o) => o.id} />
      </Card>
      <Baskets />
      <Splits />
    </Page>
  );
}
