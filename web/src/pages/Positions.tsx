import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Position } from '../api/types';
import { formatPaise } from '../lib/sizing';

export function Positions() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ['positions'], queryFn: () => request<Position[]>('/positions'), refetchInterval: 3000 });

  async function close(p: Position) {
    await request('/positions/close', { method: 'POST', idempotent: true, body: { instrumentId: p.instrumentId, product: p.product } });
    qc.invalidateQueries({ queryKey: ['positions'] });
  }
  async function closeAll() {
    await request('/positions/close-all', { method: 'POST', idempotent: true });
    qc.invalidateQueries({ queryKey: ['positions'] });
  }

  return (
    <div>
      <h1>Positions</h1>
      <button onClick={closeAll}>Close all</button>
      <table data-testid="positions-table" style={{ marginTop: 16 }}>
        <thead><tr><th>Instrument</th><th>Product</th><th>Net</th><th>Avg</th><th>Realized</th><th>Fees</th><th></th></tr></thead>
        <tbody>
          {(data ?? []).map((p) => (
            <tr key={p.id}>
              <td>{p.instrumentId.slice(0, 8)}</td><td>{p.product}</td>
              <td data-testid={`pos-net-${p.instrumentId}`}>{p.netQuantity}</td><td>{p.averagePrice}</td>
              <td>{formatPaise(p.realizedPnl.paise)}</td><td>{formatPaise(p.fees.paise)}</td>
              <td>{p.netQuantity !== 0 && <button onClick={() => close(p)}>Close</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
