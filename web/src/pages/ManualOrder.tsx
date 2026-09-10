import { FormEvent, useEffect, useRef, useState } from 'react';
import { request, ApiError } from '../api/client';
import { Instrument, Order, StopSuggestion } from '../api/types';
import { sizeByRisk } from '../lib/sizing';

export function ManualOrder({ onPlaced }: { onPlaced?: () => void }) {
  const [symbol, setSymbol] = useState('NSE:INFY');
  const [instrument, setInstrument] = useState<Instrument | null>(null);
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [orderType, setOrderType] = useState('MARKET');
  const [quantity, setQuantity] = useState(1);
  const [limitPrice, setLimitPrice] = useState('');
  const [stopPrice, setStopPrice] = useState('');
  const [targetPrice, setTargetPrice] = useState('');
  const [riskRupees, setRiskRupees] = useState('');
  const [suggestion, setSuggestion] = useState<StopSuggestion | null>(null);
  const [suggestionError, setSuggestionError] = useState('');
  const stopEdited = useRef(false); // a stop the user typed is not overwritten when the side changes
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  async function suggestStop(inst: Instrument, forSide: string, entry: string, prefill: boolean) {
    try {
      const q = `instrumentId=${inst.id}&side=${forSide}${entry ? `&entry=${encodeURIComponent(entry)}` : ''}`;
      const s = await request<StopSuggestion>(`/risk/stop-suggestion?${q}`);
      setSuggestion(s);
      setSuggestionError('');
      if (prefill) { setStopPrice(s.stop); stopEdited.current = false; }
    } catch (err) {
      setSuggestion(null);
      setSuggestionError(err instanceof ApiError ? (err.problem?.detail ?? err.message) : 'unavailable');
    }
  }

  async function resolve() {
    setMessage('');
    try {
      const inst = await request<Instrument>(`/instruments/resolve?symbol=${encodeURIComponent(symbol)}`);
      setInstrument(inst);
      await suggestStop(inst, side, limitPrice, true);
    } catch { setInstrument(null); setSuggestion(null); setMessage('Instrument not found'); }
  }

  useEffect(() => {
    if (instrument) void suggestStop(instrument, side, limitPrice, !stopEdited.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [side]);

  const sizedQty = (() => {
    if (!instrument || !riskRupees || !limitPrice || !stopPrice) return null;
    try { return sizeByRisk(Number(limitPrice), Number(stopPrice), Number(riskRupees), instrument.lotSize); }
    catch { return null; }
  })();

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!instrument) { setMessage('Resolve an instrument first'); return; }
    setBusy(true);
    setMessage('');
    try {
      const order = await request<Order>('/orders/intents', {
        method: 'POST', idempotent: true,
        body: {
          instrumentId: instrument.id, side, quantity: sizedQty ?? quantity, orderType, product: 'MIS',
          limitPrice: limitPrice || null, stopPrice: stopPrice || null, targetPrice: targetPrice || null, reason: 'MANUAL',
        },
      });
      setMessage(`Order ${order.state}`);
      onPlaced?.();
    } catch (err) {
      if (err instanceof ApiError) {
        const failed = err.problem?.checks?.filter((c: any) => !c.passed).map((c: any) => `${c.name}: ${c.message}`);
        const reasons = err.problem?.reasons ?? (failed?.length ? failed : undefined) ?? [err.problem?.detail ?? err.message];
        setMessage(`Rejected: ${Array.isArray(reasons) ? reasons.join('; ') : reasons}`);
      } else setMessage('Failed');
    } finally { setBusy(false); }
  }

  return (
    <form onSubmit={submit} style={{ border: '1px solid #ccc', padding: 12, maxWidth: 480 }}>
      <h3>Manual order</h3>
      <div>
        <input aria-label="symbol" value={symbol} onChange={(e) => setSymbol(e.target.value)} />
        <button type="button" onClick={resolve}>Resolve</button>
        {instrument && <span> {instrument.hejjeSymbol} (lot {instrument.lotSize})</span>}
      </div>
      <div style={{ marginTop: 8 }}>
        <select aria-label="side" value={side} onChange={(e) => setSide(e.target.value as 'BUY' | 'SELL')}>
          <option>BUY</option><option>SELL</option>
        </select>
        <select aria-label="orderType" value={orderType} onChange={(e) => setOrderType(e.target.value)}>
          <option>MARKET</option><option>LIMIT</option><option>SL</option><option>SL_M</option>
        </select>
        <input aria-label="quantity" type="number" value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} style={{ width: 70 }} />
      </div>
      <div style={{ marginTop: 8 }}>
        <input aria-label="limitPrice" placeholder="limit" value={limitPrice} onChange={(e) => setLimitPrice(e.target.value)} style={{ width: 90 }} />
        <input aria-label="stopPrice" placeholder="stop" value={stopPrice} onChange={(e) => { setStopPrice(e.target.value); stopEdited.current = true; }} style={{ width: 90 }} />
        <input aria-label="targetPrice" placeholder="target" value={targetPrice} onChange={(e) => setTargetPrice(e.target.value)} style={{ width: 90 }} />
        <input aria-label="risk" placeholder="risk ₹" value={riskRupees} onChange={(e) => setRiskRupees(e.target.value)} style={{ width: 80 }} />
      </div>
      {instrument && (
        <p data-testid="stop-suggestion">
          <button type="button" onClick={() => suggestStop(instrument, side, limitPrice, true)}>Suggest stop</button>
          {suggestion && <> Suggested stop {suggestion.stop} ({suggestionBasis(suggestion)}, {suggestion.distancePct}% from {suggestion.entry}; limit {suggestion.maxDistancePct}%)</>}
          {suggestionError && <> No suggestion: {suggestionError}</>}
        </p>
      )}
      {sizedQty != null && <p>Risk-based qty: {sizedQty}</p>}
      <button type="submit" disabled={busy} data-testid="place-order">{busy ? 'Placing…' : 'Place order'}</button>
      {message && <p data-testid="order-message">{message}</p>}
    </form>
  );
}

function suggestionBasis(s: StopSuggestion): string {
  if (s.basis === 'ATR') return `1.5 × ATR14 ${s.atr}`;
  if (s.basis === 'MAX_DISTANCE') return 'capped at the max stop distance';
  return '1% of entry, no recent bars';
}
