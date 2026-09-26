import { useMemo } from 'react';
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
  // the attribution is part of the row data: the table caches accessor values until its data changes, so a column that
  // looked the slug up in `strategies` kept the id fallback whenever /trades answered before /strategies
  const rows = useMemo(() => data?.map((t) => ({
    ...t,
    attribution: t.strategyId ? strategies?.find((s) => s.id === t.strategyId)?.slug ?? t.strategyId.slice(0, 8) : 'MANUAL',
  })), [data, strategies]);

  const columns: ColumnDef<Trade & { attribution: string }, any>[] = [
    { id: 'time', header: 'Time', accessorFn: (t) => t.ts, cell: (c) => <span className="num">{new Date(c.getValue()).toLocaleTimeString()}</span> },
    { accessorKey: 'side', header: 'Side' },
    { accessorKey: 'quantity', header: 'Qty', meta: { numeric: true } },
    { accessorKey: 'price', header: 'Price', meta: { numeric: true } },
    { accessorKey: 'attribution', header: 'Attribution', cell: (c) => <span data-testid="attribution">{c.getValue()}</span> },
    { id: 'review', header: 'Review', enableSorting: false, cell: (c) => {
      const review = reviews?.find((r) => r.entryOrderId === c.row.original.orderId);
      return review ? <Link to={`/reviews/${review.id}`}>Review</Link> : '';
    } },
  ];

  return (
    <Page title="Trades">
      <DataTable data-testid="trades-table" columns={columns} data={rows} loading={isLoading} empty="No trades yet" getRowId={(t) => t.id} />
    </Page>
  );
}
