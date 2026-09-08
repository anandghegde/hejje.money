/** Risk-based position sizing mirrored from the server's PositionSizer, for the manual order form preview. */
export function sizeByRisk(entry: number, stop: number, riskRupees: number, lotSize: number, maxQty = 0): number {
  if (lotSize <= 0) throw new Error('lot size must be positive');
  const perUnit = Math.abs(entry - stop);
  if (perUnit <= 0) throw new Error('entry and stop must differ');
  const rawUnits = Math.floor(riskRupees / perUnit);
  const lots = Math.floor(rawUnits / lotSize);
  let qty = lots * lotSize;
  if (maxQty > 0) qty = Math.min(qty, Math.floor(maxQty / lotSize) * lotSize);
  return Math.max(0, qty);
}

/** Formats paise as a rupee string. */
export function formatPaise(paise: number): string {
  return (paise / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
