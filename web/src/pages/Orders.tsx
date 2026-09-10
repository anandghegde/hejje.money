import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Order } from '../api/types';
import { ManualOrder } from './ManualOrder';
import { Baskets, Splits } from '../components/Baskets';

export function Orders() {
  const qc = useQueryClient();
  const { data: orders } = useQuery({ queryKey: ['orders'], queryFn: () => request<Order[]>('/orders'), refetchInterval: 3000 });

  async function cancel(id: string) {
    await request(`/orders/${id}/cancel`, { method: 'POST', idempotent: true });
    qc.invalidateQueries({ queryKey: ['orders'] });
  }

  return (
    <div>
      <h1>Orders</h1>
      <ManualOrder onPlaced={() => qc.invalidateQueries({ queryKey: ['orders'] })} />
      <table data-testid="orders-table" style={{ marginTop: 16, borderCollapse: 'collapse' }}>
        <thead><tr><th>Time</th><th>Side</th><th>Qty</th><th>Filled</th><th>Type</th><th>Avg</th><th>State</th><th>Broker ID</th><th></th></tr></thead>
        <tbody>
          {(orders ?? []).map((o) => (
            <tr key={o.id}>
              <td>{new Date(o.updatedAt).toLocaleTimeString()}</td>
              <td>{o.side}</td><td>{o.quantity}</td><td>{o.filledQuantity}</td><td>{o.orderType}</td>
              <td>{o.averagePrice}</td><td data-testid={`order-state-${o.id}`}>{o.state}</td><td>{o.brokerOrderId ?? '—'}</td>
              <td>{['OPEN', 'PARTIALLY_FILLED', 'BROKER_ACCEPTED'].includes(o.state) && <button onClick={() => cancel(o.id)}>Cancel</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Baskets />
      <Splits />
    </div>
  );
}
