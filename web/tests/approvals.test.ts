import { describe, expect, it } from 'vitest';
import { createdProposal, proposalRows, timeLeft } from '../src/lib/approvals';
import { Approval } from '../src/api/types';

const base = { id: 'a1', status: 'PENDING', mode: 'PAPER', requestedBy: 'bot', requestedByType: 'CLIENT', summary: 's', createdAt: '', expiresAt: '' } as unknown as Approval;

describe('approvals helpers', () => {
  it('counts down and reports expiry', () => {
    const now = Date.parse('2026-09-10T10:00:00Z');
    expect(timeLeft('2026-09-10T10:04:05Z', now)).toBe('4m 05s');
    expect(timeLeft('2026-09-10T10:00:12Z', now)).toBe('12s');
    expect(timeLeft('2026-09-10T09:59:59Z', now)).toBe('expired');
  });

  it('describes what approving will do', () => {
    const order = { ...base, kind: 'ORDER_NEW', proposal: { side: 'BUY', quantity: 100, instrument: 'NSE:INFY', orderType: 'MARKET', product: 'MIS', stop: 1490, maxRisk: 1000 } } as Approval;
    expect(proposalRows(order)).toContainEqual(['Order', 'BUY 100 NSE:INFY']);
    expect(proposalRows(order)).toContainEqual(['Target', '—']);
    const close = { ...base, kind: 'POSITION_CLOSE', instrument: 'NSE:INFY', proposal: { product: 'MIS', netQuantity: 10 } } as Approval;
    expect(proposalRows(close)).toEqual([['Close', 'NSE:INFY MIS (net 10)']]);
    const modify = { ...base, kind: 'ORDER_MODIFY', proposal: { orderId: 'o1', limitPrice: 1405 } } as Approval;
    expect(proposalRows(modify)).toEqual([['Order', 'o1'], ['limitPrice', '1405']]);
  });

  it('spots a created proposal in a chat trace', () => {
    expect(createdProposal([{ tool: 'prepare_order', status: 'OK' }])).toBe(false);
    expect(createdProposal([{ tool: 'submit_order_intent', status: 'DENIED' }])).toBe(false);
    expect(createdProposal([{ tool: 'submit_order_intent', status: 'OK' }])).toBe(true);
  });
});
