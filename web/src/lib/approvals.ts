import { Approval } from '../api/types';

/** Tools that create an approval (a proposal awaiting a human). */
export const PROPOSAL_TOOLS = ['submit_order_intent', 'modify_order_intent', 'cancel_order_intent', 'close_position_intent'];

export function createdProposal(trace: { tool: string; status: string }[]): boolean {
  return trace.some((t) => PROPOSAL_TOOLS.includes(t.tool) && t.status === 'OK');
}

/** "4m 05s", "12s" or "expired". */
export function timeLeft(expiresAt: string, now: number = Date.now()): string {
  const ms = Date.parse(expiresAt) - now;
  if (ms <= 0) return 'expired';
  const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60);
  return m > 0 ? `${m}m ${String(s % 60).padStart(2, '0')}s` : `${s}s`;
}

/** Label/value rows describing what approving will do. */
export function proposalRows(a: Approval): [string, string][] {
  const p = a.proposal ?? {};
  const v = (x: unknown) => (x === null || x === undefined || x === '' ? '—' : String(x));
  if (a.kind === 'ORDER_NEW') {
    return [['Order', `${v(p.side)} ${v(p.quantity)} ${v(p.instrument)}`], ['Type', `${v(p.orderType)} ${v(p.product)}`], ['Entry ≈', v(p.entry)],
      ['Stop', v(p.stop)], ['Target', v(p.target)], ['Max risk (₹)', v(p.maxRisk)], ['Strategy', v(p.strategy)], ['Autonomy level', v(p.autonomyLevel)],
      ['Event risk', v(p.eventRisk)]];
  }
  if (a.kind === 'POSITION_CLOSE') return [['Close', `${v(a.instrument)} ${v(p.product)} (net ${v(p.netQuantity)})`]];
  const rows: [string, string][] = [['Order', v(p.orderId)]];
  if (a.kind === 'ORDER_MODIFY') {
    for (const k of ['quantity', 'orderType', 'limitPrice', 'triggerPrice']) if (p[k] !== undefined) rows.push([k, v(p[k])]);
  }
  return rows;
}
