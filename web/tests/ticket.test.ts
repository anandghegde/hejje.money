import { Ticket, validateTicket } from '../src/lib/ticket';

const base: Ticket = { side: 'BUY', orderType: 'MARKET', quantity: 1, limitPrice: '', stopPrice: '1485.00', targetPrice: '', riskRupees: '', entry: 1500 };

describe('order ticket validation', () => {
  it('accepts a market order with a stop below the entry', () => {
    expect(validateTicket(base)).toEqual({});
  });

  it('needs a whole positive quantity unless sized from risk', () => {
    expect(validateTicket({ ...base, quantity: 0 }).quantity).toBeTruthy();
    expect(validateTicket({ ...base, quantity: 1.5 }).quantity).toBeTruthy();
    expect(validateTicket({ ...base, quantity: 0, riskRupees: '500', sizedQty: 33 }).quantity).toBeUndefined();
    expect(validateTicket({ ...base, riskRupees: '5', sizedQty: 0 }).riskRupees).toContain('too small');
  });

  it('needs a limit price for LIMIT orders', () => {
    expect(validateTicket({ ...base, orderType: 'LIMIT' }).limitPrice).toContain('needs a limit price');
    expect(validateTicket({ ...base, orderType: 'LIMIT', limitPrice: 'abc' }).limitPrice).toContain('positive');
    expect(validateTicket({ ...base, orderType: 'LIMIT', limitPrice: '1500' })).toEqual({});
  });

  it('puts the stop and target on the right side of the entry', () => {
    expect(validateTicket({ ...base, stopPrice: '1510' }).stopPrice).toBe('A BUY stop must be below the entry (1500)');
    expect(validateTicket({ ...base, side: 'SELL', stopPrice: '1510', targetPrice: '1480' })).toEqual({});
    expect(validateTicket({ ...base, side: 'SELL', stopPrice: '1490' }).stopPrice).toContain('above');
    expect(validateTicket({ ...base, targetPrice: '1490' }).targetPrice).toContain('above');
    expect(validateTicket({ ...base, stopPrice: '1510', entry: null })).toEqual({}); // no reference price: the server judges
  });
});
