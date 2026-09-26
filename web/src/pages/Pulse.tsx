import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { Candle, Instrument, PulseSnapshot } from '../api/types';
import { pulseTone, sectorBar, sparklinePoints } from '../lib/pulse';
import { Badge, Card, Page, signed } from '../ui';
import '../styles/research.css';

function VixSparkline() {
  const { data: vix } = useQuery({ queryKey: ['instrument', 'INDEX:INDIA VIX'],
    queryFn: () => request<Instrument>(`/instruments/resolve?symbol=${encodeURIComponent('INDEX:INDIA VIX')}`) });
  const today = new Date();
  const from = new Date(today.getTime() - 2 * 24 * 3600 * 1000).toISOString();
  const { data: candles } = useQuery({ queryKey: ['vix-candles', vix?.id], enabled: !!vix,
    queryFn: () => request<Candle[]>(`/market/candles?instrumentId=${vix!.id}&timeframe=M5&from=${from}&to=${today.toISOString()}`) });
  const values = (candles ?? []).map((c) => c.close);
  if (values.length < 2) return <div data-testid="vix-sparkline" className="muted">VIX: no candles yet</div>;
  return (
    <div data-testid="vix-sparkline">
      <div>VIX <b className="num">{values[values.length - 1]}</b></div>
      <svg width={160} height={40}><polyline className="spark-line" points={sparklinePoints(values)} /></svg>
    </div>
  );
}

/** A sector's relative change as a bar either side of the centre line (drawn in SVG units, so no inline style). */
function SectorBar({ width, positive }: { width: number; positive: boolean }) {
  const half = width / 2;
  return (
    <svg className="sector-bar" viewBox="0 0 100 8" preserveAspectRatio="none" aria-hidden="true">
      <line x1={50} x2={50} y1={0} y2={8} className="sector-bar-axis" />
      {half > 0 && <rect x={positive ? 50 : 50 - half} y={1} width={half} height={6} className={positive ? 'sector-bar-up' : 'sector-bar-down'} />}
    </svg>
  );
}

export function Pulse() {
  const { data, error } = useQuery({ queryKey: ['pulse'], queryFn: () => request<PulseSnapshot>('/context/pulse'), refetchInterval: 60_000 });
  if (error) return <Page title="Pulse"><p className="message message-loss">Pulse unavailable: {(error as Error).message}</p></Page>;
  if (!data) return <Page title="Pulse"><p>Loading…</p></Page>;
  const t = data.technical;
  const m = data.market;
  return (
    <Page title="Pulse">
      <Card data-testid="technical-pulse">
        <div className="cluster pulse-head">
          <div className="stack-sm">
            <div className="stat-label">Technical Pulse</div>
            <div className="pulse-direction"><Badge tone={pulseTone(t.direction)}>{t.direction} — {t.strength}</Badge></div>
            <div>Score {t.score} · coverage {Math.round(t.coverage * 100)}% · as of {new Date(data.asOf).toLocaleTimeString()}</div>
          </div>
          <VixSparkline />
        </div>
      </Card>
      <div className="grid-auto">
        <Card title="Market Pulse">
          <dl className="kv" data-testid="market-pulse">
            <dt>Market regime</dt><dd><b>{m.regime}</b></dd>
            <dt>Volatility</dt><dd><b>{m.volatility}</b></dd>
            <dt>Breadth</dt><dd><b>{m.breadth}</b></dd>
            <dt>Market condition</dt><dd title={m.marketConditionEvidence ?? ''}><b>{m.marketCondition ?? 'Unknown'}</b></dd>
            <dt>Global context</dt><dd><b>{m.globalContext}</b></dd>
          </dl>
        </Card>
        <Card title="Sectors">
          <div className="table-scroll">
            <table data-testid="sectors">
              <tbody>
                {m.sectors.map((s) => (
                  <tr key={s.symbol}>
                    <td>{s.name}</td>
                    <td><Badge tone={pulseTone(s.label)}>{s.label}</Badge></td>
                    <td className="num">{s.changePct == null ? '—' : signed(s.changePct, 2, '%')}</td>
                    <td className="sector-bar-cell"><SectorBar {...sectorBar(s)} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
      <Card title="Evidence">
        <ul data-testid="pulse-evidence">
          {t.evidence.map((e) => <li key={e}>{e}</li>)}
        </ul>
      </Card>
    </Page>
  );
}
