import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { Basket, SplitOrder } from '../api/types';
import { BASKET_COLOR, legSummary, placementOrder, splitProgress } from '../lib/baskets';

/** Basket status (PRD 34): legs in placement order with their state, the policy and the outcome. */
export function Baskets() {
  const { data } = useQuery({ queryKey: ['baskets'], queryFn: () => request<Basket[]>('/baskets?limit=10'), refetchInterval: 3000 });
  if (!data || data.length === 0) return null;
  return (
    <div data-testid="baskets" style={{ marginTop: 24 }}>
      <h2>Baskets</h2>
      {data.map((b) => (
        <div key={b.id} style={{ marginBottom: 12 }}>
          <b>{b.name ?? b.id.slice(0, 8)}</b> · {b.policy}{b.rollback === 'CLOSE_FILLED_LEGS' ? ' + rollback' : ''} ·{' '}
          <span style={{ color: BASKET_COLOR[b.status] }}>{b.status}</span> · {legSummary(b)}
          {b.detail && <div style={{ fontSize: 12, color: '#616161' }}>{b.detail}</div>}
          <table>
            <thead><tr><th>#</th><th>Side</th><th>Qty</th><th>Type</th><th>Hedge</th><th>State</th><th>Detail</th></tr></thead>
            <tbody>
              {placementOrder(b.legs).map((l) => (
                <tr key={l.id}><td>{l.sequence}</td><td>{l.side}</td><td>{l.quantity}</td><td>{l.orderType}</td><td>{l.hedgeFirst ? 'first' : ''}</td>
                  <td>{l.status}</td><td style={{ fontSize: 12 }}>{l.detail ?? ''}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
}

/** Split progress (PRD 35): filled share, children, and why a split stopped. */
export function Splits() {
  const { data } = useQuery({ queryKey: ['splits'], queryFn: () => request<SplitOrder[]>('/orders/splits?limit=10'), refetchInterval: 3000 });
  if (!data || data.length === 0) return null;
  return (
    <div data-testid="splits" style={{ marginTop: 24 }}>
      <h2>Split orders</h2>
      <table>
        <thead><tr><th>Side</th><th>Qty</th><th>Progress</th><th>Status</th><th>Detail</th></tr></thead>
        <tbody>
          {data.map((s) => {
            const p = splitProgress(s);
            return (
              <tr key={s.id}><td>{s.side}</td><td>{s.quantity}</td>
                <td><progress value={p.pct} max={100} /> {p.text}</td><td>{s.status}</td><td style={{ fontSize: 12 }}>{s.detail ?? ''}</td></tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
