import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { Basket, SplitOrder } from '../api/types';
import { BASKET_TONE, legSummary, placementOrder, splitProgress } from '../lib/baskets';
import { Badge, Card } from '../ui';

/** Basket status (PRD 34): legs in placement order with their state, the policy and the outcome. */
export function Baskets() {
  const { data } = useQuery({ queryKey: ['baskets'], queryFn: () => request<Basket[]>('/baskets?limit=10'), refetchInterval: 3000 });
  if (!data || data.length === 0) return null;
  return (
    <Card title="Baskets" data-testid="baskets">
      <div className="stack">
        {data.map((b) => (
          <div key={b.id} className="stack-sm">
            <div className="cluster">
              <b>{b.name ?? b.id.slice(0, 8)}</b> · {b.policy}{b.rollback === 'CLOSE_FILLED_LEGS' ? ' + rollback' : ''} ·
              <Badge tone={BASKET_TONE[b.status]}>{b.status}</Badge> · {legSummary(b)}
            </div>
            {b.detail && <div className="muted text-sm">{b.detail}</div>}
            <div className="table-scroll">
              <table>
                <thead><tr><th className="num">#</th><th>Side</th><th className="num">Qty</th><th>Type</th><th>Hedge</th><th>State</th><th>Detail</th></tr></thead>
                <tbody>
                  {placementOrder(b.legs).map((l) => (
                    <tr key={l.id}><td className="num">{l.sequence}</td><td>{l.side}</td><td className="num">{l.quantity}</td><td>{l.orderType}</td><td>{l.hedgeFirst ? 'first' : ''}</td>
                      <td>{l.status}</td><td className="why">{l.detail ?? ''}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

/** Split progress (PRD 35): filled share, children, and why a split stopped. */
export function Splits() {
  const { data } = useQuery({ queryKey: ['splits'], queryFn: () => request<SplitOrder[]>('/orders/splits?limit=10'), refetchInterval: 3000 });
  if (!data || data.length === 0) return null;
  return (
    <Card title="Split orders" data-testid="splits">
      <div className="table-scroll">
        <table>
          <thead><tr><th>Side</th><th className="num">Qty</th><th>Progress</th><th>Status</th><th>Detail</th></tr></thead>
          <tbody>
            {data.map((s) => {
              const p = splitProgress(s);
              return (
                <tr key={s.id}><td>{s.side}</td><td className="num">{s.quantity}</td>
                  <td><progress value={p.pct} max={100} /> {p.text}</td><td>{s.status}</td><td className="why">{s.detail ?? ''}</td></tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </Card>
  );
}
