import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { Candle, Instrument, PulseSnapshot } from '../api/types';
import { pulseColor, sectorBar, sparklinePoints } from '../lib/pulse';

function VixSparkline() {
  const { data: vix } = useQuery({ queryKey: ['instrument', 'INDEX:INDIA VIX'],
    queryFn: () => request<Instrument>(`/instruments/resolve?symbol=${encodeURIComponent('INDEX:INDIA VIX')}`) });
  const today = new Date();
  const from = new Date(today.getTime() - 2 * 24 * 3600 * 1000).toISOString();
  const { data: candles } = useQuery({ queryKey: ['vix-candles', vix?.id], enabled: !!vix,
    queryFn: () => request<Candle[]>(`/market/candles?instrumentId=${vix!.id}&timeframe=M5&from=${from}&to=${today.toISOString()}`) });
  const values = (candles ?? []).map((c) => c.close);
  if (values.length < 2) return <div data-testid="vix-sparkline">VIX: no candles yet</div>;
  return (
    <div data-testid="vix-sparkline">
      <div>VIX <b>{values[values.length - 1]}</b></div>
      <svg width={160} height={40}><polyline fill="none" stroke="#616161" strokeWidth={1.5} points={sparklinePoints(values)} /></svg>
    </div>
  );
}

export function Pulse() {
  const { data, error } = useQuery({ queryKey: ['pulse'], queryFn: () => request<PulseSnapshot>('/context/pulse'), refetchInterval: 60_000 });
  if (error) return <p>Pulse unavailable: {(error as Error).message}</p>;
  if (!data) return <p>Loading…</p>;
  const t = data.technical;
  const m = data.market;
  return (
    <div>
      <h2>Pulse</h2>
      <div data-testid="technical-pulse" style={{ display: 'flex', gap: 24, alignItems: 'center', padding: 12, background: '#f4f6f8', borderRadius: 6 }}>
        <div>
          <div style={{ fontSize: 12, color: '#616161' }}>Technical Pulse</div>
          <div style={{ fontSize: 24, fontWeight: 700, color: pulseColor(t.direction) }}>{t.direction} — {t.strength}</div>
          <div>Score {t.score} · coverage {Math.round(t.coverage * 100)}% · as of {new Date(data.asOf).toLocaleTimeString()}</div>
        </div>
        <VixSparkline />
      </div>
      <div style={{ display: 'flex', gap: 32, marginTop: 16, flexWrap: 'wrap' }}>
        <div>
          <h3>Market Pulse</h3>
          <table data-testid="market-pulse">
            <tbody>
              <tr><td>Market regime</td><td><b>{m.regime}</b></td></tr>
              <tr><td>Volatility</td><td><b>{m.volatility}</b></td></tr>
              <tr><td>Breadth</td><td><b>{m.breadth}</b></td></tr>
              <tr><td>Market condition</td><td title={m.marketConditionEvidence ?? ''}><b>{m.marketCondition ?? 'Unknown'}</b></td></tr>
              <tr><td>Global context</td><td><b>{m.globalContext}</b></td></tr>
            </tbody>
          </table>
        </div>
        <div>
          <h3>Sectors</h3>
          <table data-testid="sectors">
            <tbody>
              {m.sectors.map((s) => {
                const bar = sectorBar(s);
                return (
                  <tr key={s.symbol}>
                    <td>{s.name}</td>
                    <td style={{ color: pulseColor(s.label), fontWeight: 600 }}>{s.label}</td>
                    <td>{s.changePct == null ? '—' : `${s.changePct > 0 ? '+' : ''}${s.changePct.toFixed(2)}%`}</td>
                    <td style={{ width: 120 }}>
                      <div style={{ height: 8, width: `${bar.width}%`, background: bar.positive ? '#2e7d32' : '#c62828', marginLeft: bar.positive ? '50%' : `${50 - bar.width / 2}%` }} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
      <h3>Evidence</h3>
      <ul data-testid="pulse-evidence">
        {t.evidence.map((e) => <li key={e}>{e}</li>)}
      </ul>
    </div>
  );
}
