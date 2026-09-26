import { Basket, BasketLeg, SplitOrder } from '../api/types';
import type { BadgeTone } from '../ui';

export const BASKET_TONE: Record<Basket['status'], BadgeTone> = {
  PENDING: 'neutral', EXECUTING: 'info', COMPLETED: 'profit', PARTIAL: 'warning', FAILED: 'loss', ROLLED_BACK: 'warning', EXPIRED: 'loss',
};

/** Legs in the order they were (or will be) placed: hedge legs first, then by sequence (mirrors the server). */
export function placementOrder(legs: BasketLeg[]): BasketLeg[] {
  return [...legs].sort((a, b) => (a.executionOrder ?? 99) - (b.executionOrder ?? 99) || Number(b.hedgeFirst) - Number(a.hedgeFirst) || a.sequence - b.sequence);
}

export function legSummary(b: Basket): string {
  const filled = b.legs.filter((l) => l.status === 'FILLED').length;
  const rolled = b.legs.filter((l) => l.status === 'ROLLED_BACK').length;
  return `${filled}/${b.legs.length} filled` + (rolled ? `, ${rolled} rolled back` : '');
}

/** Filled share of a split (0-100) and a one-line description. */
export function splitProgress(s: SplitOrder): { pct: number; text: string } {
  const pct = s.quantity === 0 ? 0 : Math.round((s.filledQuantity / s.quantity) * 100);
  return { pct, text: `${s.filledQuantity}/${s.quantity} in ${s.children} child order(s) of ≤ ${s.policy.maxChildQuantity}` };
}
