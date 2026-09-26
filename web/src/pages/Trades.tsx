import { useQuery } from '@tanstack/react-query';
import { ColumnDef } from '@tanstack/react-table';
import { Link } from 'react-router-dom';
import { request } from '../api/client';
import { Strategy, Trade, TradeReview } from '../api/types';
import { DataTable, Page } from '../ui';

export function Trades() {
  const { data, isLoading } = useQuery({ queryKey: ['trades'], queryFn: () => request<Trade[]>('/trades'), refetchInterval: 5000 });
  const { data: strategies } = useQuery({ queryKey: ['strategies'], queryFn: () => request<Strategy[]>('/strategies') });
  const { data: reviews } = useQuery({ queryKey: ['reviews'], queryFn: () => request<TradeReview[]>('/reviews'), refetchInterval: 5000 });
  const slug = (id?: string) => (id ? strategies?.find((s) => s.id === id)?.slug ?? id.slice(0, 8) : 'MANUAL');

  const columns: ColumnDef<Trade, any>[] = [
    { id: 'time', header: 'Time', accessorFn: (t) => t.ts, cell: (c) => <span className="num">{new Date(c.getValue()).toLocaleTimeString()}</span> },
    { accessorKey: 'side', header: 'Side' },
    { accessorKey: 'quantity', header: 'Qty', meta: { numeric: true } },
    { accessorKey: 'price', header: 'Price', meta: { numeric: true } },
    { id: 'attribution', header: 'Attribution', accessorFn: (t) => slug(t.strategyId), cell: (c) => <span data-testid="attribution">{c.getValue()}</span> },
    { id: 'review', header: 'Review', enableSorting: false, cell: (c) => {
      const review = reviews?.find((r) => r.entryOrderId === c.row.original.orderId);
      return review ? <Link to={`/reviews/${review.id}`}>Review</Link> : '';
    } },
  ];

  return (
    <Page title="Trades">
      <DataTable data-testid="trades-table" columns={columns} data={data} loading={isLoading} empty="No trades yet" getRowId={(t) => t.id} />
    </Page>
  );
}
