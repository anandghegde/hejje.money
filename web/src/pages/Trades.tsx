import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { request } from '../api/client';
import { Strategy, Trade, TradeReview } from '../api/types';

export function Trades() {
  const { data } = useQuery({ queryKey: ['trades'], queryFn: () => request<Trade[]>('/trades'), refetchInterval: 5000 });
  const { data: strategies } = useQuery({ queryKey: ['strategies'], queryFn: () => request<Strategy[]>('/strategies') });
  const { data: reviews } = useQuery({ queryKey: ['reviews'], queryFn: () => request<TradeReview[]>('/reviews'), refetchInterval: 5000 });
  const slug = (id?: string) => (id ? strategies?.find((s) => s.id === id)?.slug ?? id.slice(0, 8) : 'MANUAL');
  return (
    <div>
      <h1>Trades</h1>
      <table data-testid="trades-table">
        <thead><tr><th>Time</th><th>Side</th><th>Qty</th><th>Price</th><th>Attribution</th><th>Review</th></tr></thead>
        <tbody>
          {(data ?? []).map((t) => {
            const review = reviews?.find((r) => r.entryOrderId === t.orderId);
            return (
              <tr key={t.id}><td>{new Date(t.ts).toLocaleTimeString()}</td><td>{t.side}</td><td>{t.quantity}</td><td>{t.price}</td>
                <td data-testid="attribution">{slug(t.strategyId)}</td>
                <td>{review ? <Link to={`/reviews/${review.id}`}>Review</Link> : ''}</td></tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
