/** Client-side checks for the manual order ticket; the server's risk checks still decide. Empty result = valid. */
export interface Ticket {
  side: 'BUY' | 'SELL';
  orderType: string;
  quantity: number;
  limitPrice: string;
  stopPrice: string;
  targetPrice: string;
  riskRupees: string;
  /** the price the stop and target are judged against: the limit price, else the suggestion's entry */
  entry?: number | null;
  /** quantity sized from the risk amount, when one is given */
  sizedQty?: number | null;
}

export type TicketErrors = Partial<Record<'quantity' | 'limitPrice' | 'stopPrice' | 'targetPrice' | 'riskRupees', string>>;

const positive = (v: string) => v.trim() !== '' && Number.isFinite(Number(v)) && Number(v) > 0;

export function validateTicket(t: Ticket): TicketErrors {
  const e: TicketErrors = {};
  if (t.sizedQty == null && (!Number.isInteger(t.quantity) || t.quantity < 1)) e.quantity = 'Quantity must be a whole number of at least 1';
  if (t.orderType === 'LIMIT' && t.limitPrice.trim() === '') e.limitPrice = 'A LIMIT order needs a limit price';
  else if (t.limitPrice.trim() !== '' && !positive(t.limitPrice)) e.limitPrice = 'Limit price must be a positive number';
  if (t.riskRupees.trim() !== '' && !positive(t.riskRupees)) e.riskRupees = 'Risk must be a positive amount';
  else if (t.sizedQty === 0) e.riskRupees = 'Risk is too small for one lot at this stop';

  const entry = t.entry ?? null;
  const long = t.side === 'BUY';
  if (t.stopPrice.trim() !== '') {
    if (!positive(t.stopPrice)) e.stopPrice = 'Stop must be a positive number';
    else if (entry != null && (long ? Number(t.stopPrice) >= entry : Number(t.stopPrice) <= entry)) {
      e.stopPrice = `A ${t.side} stop must be ${long ? 'below' : 'above'} the entry (${entry})`;
    }
  }
  if (t.targetPrice.trim() !== '') {
    if (!positive(t.targetPrice)) e.targetPrice = 'Target must be a positive number';
    else if (entry != null && (long ? Number(t.targetPrice) <= entry : Number(t.targetPrice) >= entry)) {
      e.targetPrice = `A ${t.side} target must be ${long ? 'above' : 'below'} the entry (${entry})`;
    }
  }
  return e;
}
