import { useQuery } from '@tanstack/react-query';
import { ColumnDef } from '@tanstack/react-table';
import { Link, useParams } from 'react-router-dom';
import { request } from '../api/client';
import { TradeReview } from '../api/types';
import { formatR } from '../lib/today';
import { Card, DataTable, Page, signed, tone } from '../ui';

const net = (paise: number) => <span className={`tone-${tone(paise)}`}>{signed(paise / 100)}</span>;

export function ReviewDetail() {
  const { id } = useParams();
  const { data: r } = useQuery({ queryKey: ['review', id], queryFn: () => request<TradeReview>(`/reviews/${id}`) });
  if (!r) return <Page title="Post-trade review"><p>Loading…</p></Page>;
  return (
    <Page title="Post-trade review" actions={<Link to="/reviews">← Reviews</Link>}>
      <Card data-testid="review-detail">
        <dl className="kv">
          <dt>Strategy</dt><dd>{r.strategyId ? <Link to={`/strategies/${r.strategyId}`}>{r.strategyId.slice(0, 8)}</Link> : 'MANUAL'}</dd>
          <dt>Outcome</dt><dd data-testid="review-outcome"><span className={`num tone-${tone(r.outcomeR)}`}>{formatR(r.outcomeR)}</span> ({net(r.netPnl.paise)} net)</dd>
          <dt>Expected setup</dt><dd>{r.expectedSetupValid == null ? '—' : r.expectedSetupValid ? 'VALID' : 'INVALID'}</dd>
          <dt>Entry slippage</dt><dd>{r.entrySlippageBps != null ? `${r.entrySlippageBps} bps` : '—'}</dd>
          <dt>Exit slippage</dt><dd>{r.exitSlippageBps != null ? `${r.exitSlippageBps} bps` : '—'}</dd>
          <dt>Close reason</dt><dd>{r.closeReason ?? '—'}</dd>
          <dt>Cause</dt><dd data-testid="review-cause">{r.cause ? `${r.cause.cause}${r.cause.complete ? '' : ' (provisional)'}` : '—'}
            {r.cause?.jevCause ? ` · Jev: ${r.cause.jevCause}` : ''}</dd>
          <dt>Entry timing</dt><dd>{r.cause?.entryTiming ?? '—'}{r.cause?.jevTiming ? ` · Jev: ${r.cause.jevTiming}` : ''}</dd>
          <dt>MFE / MAE</dt><dd>{r.cause ? `${formatR(r.cause.mfeR)} / ${formatR(r.cause.maeR)}` : '—'}</dd>
          <dt>Rule adherence</dt><dd>{r.ruleAdherencePct != null ? `${r.ruleAdherencePct}%` : '—'}</dd>
          <dt>Context</dt><dd>{Object.entries(r.context).map(([k, v]) => `${k}: ${v}`).join(', ')}</dd>
          <dt>Trade</dt><dd>{r.side} {r.quantity} @ {r.entryPrice} → {r.exitPrice} ({new Date(r.openedAt).toLocaleString()} – {new Date(r.closedAt).toLocaleTimeString()})</dd>
          <dt>Notes</dt><dd>{r.notes}</dd>
        </dl>
      </Card>
    </Page>
  );
}

export function Reviews() {
  const { data, isLoading } = useQuery({ queryKey: ['reviews'], queryFn: () => request<TradeReview[]>('/reviews'), refetchInterval: 5000 });
  const columns: ColumnDef<TradeReview, any>[] = [
    { id: 'closed', header: 'Closed', accessorFn: (r) => r.closedAt, cell: (c) => new Date(c.getValue()).toLocaleString() },
    { id: 'strategy', header: 'Strategy', accessorFn: (r) => (r.strategyId ? r.strategyId.slice(0, 8) : 'MANUAL') },
    { accessorKey: 'side', header: 'Side' },
    { accessorKey: 'quantity', header: 'Qty', meta: { numeric: true } },
    { id: 'net', header: 'Net', accessorFn: (r) => r.netPnl.paise, meta: { numeric: true }, cell: (c) => net(c.getValue()) },
    { accessorKey: 'outcomeR', header: 'R', meta: { numeric: true }, cell: (c) => <span className={`tone-${tone(c.getValue())}`}>{formatR(c.getValue())}</span> },
    { id: 'reason', header: 'Reason', accessorFn: (r) => r.closeReason ?? '—' },
    { id: 'cause', header: 'Cause', accessorFn: (r) => r.cause?.cause ?? '—' },
    { id: 'timing', header: 'Timing', accessorFn: (r) => r.cause?.entryTiming ?? '—' },
    { id: 'link', header: '', enableSorting: false, cell: (c) => <Link to={`/reviews/${c.row.original.id}`}>Review</Link> },
  ];
  return (
    <Page title="Reviews">
      <DataTable data-testid="reviews-table" columns={columns} data={data} loading={isLoading} empty="No reviews yet" getRowId={(r) => r.id} />
    </Page>
  );
}
