import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { OptionChain, OptionQuote, OptionsPosition } from '../api/types';
import { formatPaise } from '../lib/sizing';
import { moneyness, pct } from '../lib/options';
import { Card, EmptyState, Field, Page } from '../ui';
import '../styles/research.css';

const fmt = (v?: number, d = 2) => (v == null ? '—' : v.toFixed(d));

function Side({ q }: { q?: OptionQuote }) {
  if (!q) return <><td /><td /><td /><td /></>;
  return <><td className="num">{q.oi.toLocaleString()}</td><td className="num">{pct(q.iv)}</td><td className="num">{fmt(q.delta, 2)}</td><td className="num option-last">{fmt(q.last)}{q.stale ? '*' : ''}</td></>;
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
    <Page title="Options">
      <Card title="Option chain">
        <div className="stack">
          <div className="cluster">
            <Field label="Underlying">
              <select value={underlying} onChange={(e) => { setUnderlying(e.target.value); setExpiry(''); }}>
                {['NIFTY', 'BANKNIFTY', 'FINNIFTY'].map((u) => <option key={u}>{u}</option>)}
              </select>
            </Field>
            <Field label="Expiry">
              <select value={expiry} onChange={(e) => setExpiry(e.target.value)}>
                {(expiries?.expiries ?? []).map((e) => <option key={e}>{e}</option>)}
              </select>
            </Field>
          </div>
          {chain && (
            <div data-testid="option-chain" className="stack-sm">
              <p>
                Forward <b className="num">{fmt(chain.forward)}</b> ({chain.forwardSource ?? 'none'}) · ATM <b>{chain.atmStrike ?? '—'}</b> · PCR (OI) {fmt(chain.pcrOi)} · PCR (volume) {fmt(chain.pcrVolume)}
                {' '}· Max pain <b>{chain.maxPain ?? '—'}</b>
              </p>
              {chain.notes.map((n) => <p key={n} className="tone-warning">{n}</p>)}
              <div className="table-scroll">
                <table>
                  <thead><tr><th className="num">OI</th><th className="num">IV</th><th className="num">Δ</th><th className="num">Call</th><th className="option-strike">Strike</th><th className="num">Put</th><th className="num">Δ</th><th className="num">IV</th><th className="num">OI</th></tr></thead>
                  <tbody>
                    {chain.rows.map((r) => (
                      <tr key={r.strike} className={r.strike === chain.atmStrike ? 'option-atm' : undefined}>
                        <Side q={r.call} />
                        <td className="option-strike"><b>{r.strike}</b> <small>{moneyness(r.strike, chain.atmStrike, 'CE')}</small></td>
                        {r.put ? <><td className="num option-last">{fmt(r.put.last)}{r.put.stale ? '*' : ''}</td><td className="num">{fmt(r.put.delta, 2)}</td><td className="num">{pct(r.put.iv)}</td><td className="num">{r.put.oi.toLocaleString()}</td></>
                          : <><td /><td /><td /><td /></>}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      </Card>
      <Card title="Options positions">
        {(positions ?? []).length === 0 ? <EmptyState title="No options positions" /> : (
          <div className="table-scroll">
            <table>
              <thead><tr><th>Opened</th><th>Underlying</th><th>Legs</th><th>Status</th><th>Exit</th><th className="num">Realized</th></tr></thead>
              <tbody>
                {(positions ?? []).map((p) => (
                  <tr key={p.id}>
                    <td>{new Date(p.openedAt).toLocaleString()}</td><td>{p.underlying} {p.direction ?? 'NEUTRAL'}</td>
                    <td>{p.legs.map((l) => `${l.side} ${l.quantity} ${l.symbol}${l.entryPrice != null ? ` @${l.entryPrice}` : ''}`).join(' · ')}</td>
                    <td>{p.status}</td><td>{p.closeReason ?? ''}</td>
                    <td className={`num tone-${p.realized ? (p.realized.paise > 0 ? 'profit' : p.realized.paise < 0 ? 'loss' : 'neutral') : 'neutral'}`}>{p.realized ? formatPaise(p.realized.paise) : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </Page>
  );
}
