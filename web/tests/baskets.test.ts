import { describe, expect, it } from 'vitest';
import { legSummary, placementOrder, splitProgress } from '../src/lib/baskets';
import { Basket, BasketLeg, SplitOrder } from '../src/api/types';

const leg = (sequence: number, hedgeFirst: boolean, status: BasketLeg['status'], executionOrder?: number): BasketLeg => ({
  id: `l${sequence}`, sequence, hedgeFirst, executionOrder, instrumentId: 'i', side: 'BUY', quantity: 1, orderType: 'MARKET', product: 'MIS', status,
});

describe('basket and split helpers', () => {
  it('orders legs as placed: hedges first before placement, then the recorded order', () => {
    expect(placementOrder([leg(1, false, 'PENDING'), leg(2, true, 'PENDING'), leg(3, false, 'PENDING')]).map((l) => l.sequence)).toEqual([2, 1, 3]);
    expect(placementOrder([leg(1, false, 'FILLED', 1), leg(2, true, 'FILLED', 0)]).map((l) => l.sequence)).toEqual([2, 1]);
  });
  it('summarises fills and rollbacks', () => {
    const b = { legs: [leg(1, false, 'ROLLED_BACK'), leg(2, false, 'FAILED'), leg(3, false, 'SKIPPED')] } as Basket;
    expect(legSummary(b)).toBe('0/3 filled, 1 rolled back');
  });
  it('reports split progress', () => {
    const s = { quantity: 300, filledQuantity: 100, children: 1, policy: { maxChildQuantity: 100 } } as SplitOrder;
    expect(splitProgress(s)).toEqual({ pct: 33, text: '100/300 in 1 child order(s) of ≤ 100' });
  });
});
