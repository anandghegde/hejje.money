import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { Trade } from '../api/types';

export function Trades() {
  const { data } = useQuery({ queryKey: ['trades'], queryFn: () => request<Trade[]>('/trades'), refetchInterval: 5000 });
  return (
    <div>
      <h1>Trades</h1>
      <table>
        <thead><tr><th>Time</th><th>Side</th><th>Qty</th><th>Price</th></tr></thead>
        <tbody>
          {(data ?? []).map((t) => (
            <tr key={t.id}><td>{new Date(t.ts).toLocaleTimeString()}</td><td>{t.side}</td><td>{t.quantity}</td><td>{t.price}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
