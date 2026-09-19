import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { OptionChain, OptionQuote, OptionsPosition } from '../api/types';
import { formatPaise } from '../lib/sizing';
import { moneyness, pct } from '../lib/options';

const fmt = (v?: number, d = 2) => (v == null ? '—' : v.toFixed(d));

function Side({ q }: { q?: OptionQuote }) {
  if (!q) return <><td /><td /><td /><td /></>;
  return <><td>{q.oi.toLocaleString()}</td><td>{pct(q.iv)}</td><td>{fmt(q.delta, 2)}</td><td style={{ fontWeight: 600 }}>{fmt(q.last)}{q.stale ? '*' : ''}</td></>;
}

/** Option chain (plan M5.4): IV and greeks from Black-76 on the future, ATM, PCR, max pain; and the options positions. */
export function Options() {
  const [underlying, setUnderlying] = useState('NIFTY');
  const [expiry, setExpiry] = useState<string>('');
  const { data: expiries } = useQuery({ queryKey: ['option-expiries', underlying],
    queryFn: () => request<{ expiries: string[] }>(`/instruments/options/expiries?underlying=${underlying}`) });
  useEffect(() => { if (expiries?.expiries.length && !expiries.expiries.includes(expiry)) setExpiry(expiries.expiries[0]); }, [expiries, expiry]);
  const { data: chain } = useQuery({ queryKey: ['option-chain', underlying, expiry], enabled: !!expiry, refetchInterval: 5000,
    queryFn: () => request<OptionChain>(`/instruments/options/chain?underlying=${underlying}&expiry=${expiry}`) });
  const { data: positions } = useQuery({ queryKey: ['options-positions'], queryFn: () => request<OptionsPosition[]>('/options/positions?limit=10'), refetchInterval: 5000 });
  return (
    <div>
      <h1>Options</h1>
      <div style={{ display: 'flex', gap: 8 }}>
        <select value={underlying} onChange={(e) => { setUnderlying(e.target.value); setExpiry(''); }}>
          {['NIFTY', 'BANKNIFTY', 'FINNIFTY'].map((u) => <option key={u}>{u}</option>)}
        </select>
        <select value={expiry} onChange={(e) => setExpiry(e.target.value)}>
          {(expiries?.expiries ?? []).map((e) => <option key={e}>{e}</option>)}
        </select>
      </div>
      {chain && (
        <div data-testid="option-chain">
          <p>
            Forward <b>{fmt(chain.forward)}</b> ({chain.forwardSource ?? 'none'}) · ATM <b>{chain.atmStrike ?? '—'}</b> · PCR (OI) {fmt(chain.pcrOi)} · PCR (volume) {fmt(chain.pcrVolume)}
            {' '}· Max pain <b>{chain.maxPain ?? '—'}</b>
          </p>
          {chain.notes.map((n) => <p key={n} style={{ color: '#b7791f' }}>{n}</p>)}
          <table>
            <thead><tr><th>OI</th><th>IV</th><th>Δ</th><th>Call</th><th>Strike</th><th>Put</th><th>Δ</th><th>IV</th><th>OI</th></tr></thead>
            <tbody>
              {chain.rows.map((r) => (
                <tr key={r.strike} style={{ background: r.strike === chain.atmStrike ? '#fff7d6' : undefined }}>
                  <Side q={r.call} />
                  <td style={{ textAlign: 'center' }}><b>{r.strike}</b> <small>{moneyness(r.strike, chain.atmStrike, 'CE')}</small></td>
                  {r.put ? <><td style={{ fontWeight: 600 }}>{fmt(r.put.last)}{r.put.stale ? '*' : ''}</td><td>{fmt(r.put.delta, 2)}</td><td>{pct(r.put.iv)}</td><td>{r.put.oi.toLocaleString()}</td></>
                    : <><td /><td /><td /><td /></>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h2>Options positions</h2>
      <table>
        <thead><tr><th>Opened</th><th>Underlying</th><th>Legs</th><th>Status</th><th>Exit</th><th>Realized</th></tr></thead>
        <tbody>
          {(positions ?? []).map((p) => (
            <tr key={p.id}>
              <td>{new Date(p.openedAt).toLocaleString()}</td><td>{p.underlying} {p.direction ?? 'NEUTRAL'}</td>
              <td>{p.legs.map((l) => `${l.side} ${l.quantity} ${l.symbol}${l.entryPrice != null ? ` @${l.entryPrice}` : ''}`).join(' · ')}</td>
              <td>{p.status}</td><td>{p.closeReason ?? ''}</td><td>{p.realized ? formatPaise(p.realized.paise) : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
