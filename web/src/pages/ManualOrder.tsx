import { FormEvent, useState } from 'react';
import { request, ApiError } from '../api/client';
import { Instrument, Order } from '../api/types';
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
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  async function resolve() {
    setMessage('');
    try { setInstrument(await request<Instrument>(`/instruments/resolve?symbol=${encodeURIComponent(symbol)}`)); }
    catch { setInstrument(null); setMessage('Instrument not found'); }
  }

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
        <input aria-label="stopPrice" placeholder="stop" value={stopPrice} onChange={(e) => setStopPrice(e.target.value)} style={{ width: 90 }} />
        <input aria-label="targetPrice" placeholder="target" value={targetPrice} onChange={(e) => setTargetPrice(e.target.value)} style={{ width: 90 }} />
        <input aria-label="risk" placeholder="risk ₹" value={riskRupees} onChange={(e) => setRiskRupees(e.target.value)} style={{ width: 80 }} />
      </div>
      {sizedQty != null && <p>Risk-based qty: {sizedQty}</p>}
      <button type="submit" disabled={busy} data-testid="place-order">{busy ? 'Placing…' : 'Place order'}</button>
      {message && <p data-testid="order-message">{message}</p>}
    </form>
  );
}
