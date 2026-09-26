import { FormEvent, useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request, ApiError } from '../api/client';
import { Health, Instrument, Order, StopSuggestion } from '../api/types';
import { sizeByRisk } from '../lib/sizing';
import { validateTicket } from '../lib/ticket';
import { Button, Card, Dialog, Field, toNumber } from '../ui';
import '../styles/trading.css';

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
  const [confirmLive, setConfirmLive] = useState(false);
  const { data: health } = useQuery({ queryKey: ['health'], queryFn: () => request<Health>('/server/health') });
  const live = health?.mode === 'CONFIRM' || health?.mode === 'AUTO';

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

  const errors = validateTicket({
    side, orderType, quantity, limitPrice, stopPrice, targetPrice, riskRupees, sizedQty,
    entry: toNumber(limitPrice) ?? toNumber(suggestion?.entry),
  });
  const valid = Object.keys(errors).length === 0;
  const summary = instrument && [
    `${side} ${sizedQty ?? quantity} ${instrument.hejjeSymbol} ${orderType}${limitPrice ? ` @ ${limitPrice}` : ''}`,
    stopPrice && `stop ${stopPrice}`, targetPrice && `target ${targetPrice}`, riskRupees && `risk ₹${riskRupees}`,
  ].filter(Boolean).join(' · ');

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!instrument) { setMessage('Resolve an instrument first'); return; }
    if (!valid) { setMessage('Fix the highlighted fields first'); return; }
    if (live) setConfirmLive(true); // real money: one more look before it goes to the broker
    else void place();
  }

  async function place() {
    if (!instrument) return;
    setConfirmLive(false);
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
    <Card title="Manual order" className="ticket">
      <form onSubmit={submit} className="stack" noValidate>
        <div className="ticket-symbol">
          <Field label="Symbol" hint={instrument ? `${instrument.hejjeSymbol} (lot ${instrument.lotSize})` : 'Exchange and symbol, then Resolve'}>
            <input aria-label="symbol" value={symbol} onChange={(e) => setSymbol(e.target.value)} />
          </Field>
          <Button onClick={resolve}>Resolve</Button>
        </div>
        <div className="ticket-row">
          <Field label="Side">
            <select aria-label="side" value={side} onChange={(e) => setSide(e.target.value as 'BUY' | 'SELL')}>
              <option>BUY</option><option>SELL</option>
            </select>
          </Field>
          <Field label="Order type">
            <select aria-label="orderType" value={orderType} onChange={(e) => setOrderType(e.target.value)}>
              <option>MARKET</option><option>LIMIT</option><option>SL</option><option>SL_M</option>
            </select>
          </Field>
          <Field label="Qty" error={errors.quantity}>
            <input aria-label="quantity" type="number" inputMode="numeric" value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} />
          </Field>
        </div>
        <div className="ticket-row">
          <Field label="Limit" error={errors.limitPrice}>
            <input aria-label="limitPrice" inputMode="decimal" placeholder="limit" value={limitPrice} onChange={(e) => setLimitPrice(e.target.value)} />
          </Field>
          <Field label="Stop" error={errors.stopPrice}>
            <input aria-label="stopPrice" inputMode="decimal" placeholder="stop" value={stopPrice} onChange={(e) => { setStopPrice(e.target.value); stopEdited.current = true; }} />
          </Field>
          <Field label="Target" error={errors.targetPrice}>
            <input aria-label="targetPrice" inputMode="decimal" placeholder="target" value={targetPrice} onChange={(e) => setTargetPrice(e.target.value)} />
          </Field>
          <Field label="Risk ₹" error={errors.riskRupees} hint={sizedQty != null ? `Risk-based qty: ${sizedQty}` : undefined}>
            <input aria-label="risk" inputMode="decimal" placeholder="risk ₹" value={riskRupees} onChange={(e) => setRiskRupees(e.target.value)} />
          </Field>
        </div>
        {instrument && (
          <p data-testid="stop-suggestion" className="cluster text-sm">
            <Button size="sm" onClick={() => suggestStop(instrument, side, limitPrice, true)}>Suggest stop</Button>
            {suggestion && <span> Suggested stop {suggestion.stop} ({suggestionBasis(suggestion)}, {suggestion.distancePct}% from {suggestion.entry}; limit {suggestion.maxDistancePct}%)</span>}
            {suggestionError && <span> No suggestion: {suggestionError}</span>}
          </p>
        )}
        {summary && <p data-testid="order-summary" className="message ticket-summary">{summary}</p>}
        <div className="cluster">
          <Button type="submit" variant="primary" disabled={busy} data-testid="place-order">{busy ? 'Placing…' : 'Place order'}</Button>
        </div>
        {message && <p data-testid="order-message" className={message.startsWith('Rejected') || message === 'Failed' ? 'message message-loss' : 'message'}>{message}</p>}
      </form>
      <Dialog
        open={confirmLive}
        title="Place a LIVE order?"
        onClose={() => setConfirmLive(false)}
        actions={<>
          <Button onClick={() => setConfirmLive(false)}>Back</Button>
          <Button variant="danger" data-testid="confirm-live-order" onClick={() => void place()}>Place LIVE order</Button>
        </>}
      >
        <p>{summary}</p>
        <p>This goes to the broker with real money ({health?.mode}).</p>
      </Dialog>
    </Card>
  );
}

function suggestionBasis(s: StopSuggestion): string {
  if (s.basis === 'ATR') return `1.5 × ATR14 ${s.atr}`;
  if (s.basis === 'MAX_DISTANCE') return 'capped at the max stop distance';
  return '1% of entry, no recent bars';
}
