import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { createChart } from 'lightweight-charts';
import { request } from '../api/client';
import { AnalogMatch, AnalogOutcome, AnalogSummary, Base, Candle, DailyRating, Instrument, ScreenResult, WatchlistItem } from '../api/types';
import { LOOKBACKS, bandPoints, chartBase, directionTone, isClosed, num, pathPoints, rateWithCount, sessionLine, signedPct, sma, sortMatches } from '../lib/context';
import { Disabled, SurveillanceBadge } from './Screener';
import { Badge, Button, Card, Page, chartOptions, tone, useChartTheme } from '../ui';
import '../styles/research.css';

const NOT_VALIDATED = 'Not validated: shown as context, it changes no score or decision (docs/strategies/context-validation.md).';

function RatingsBlock({ r }: { r: DailyRating }) {
  const cell = (label: string, value: string | number | undefined, title?: string) => (
    <div title={title} className="stat"><div className="stat-label">{label}</div><div className="stat-value num">{value ?? '—'}</div></div>);
  return (
    <Card data-testid="ratings-block"><div className="stat-grid">
      {cell('RS rating', r.rsRating, '1-99 percentile of the weighted 3/6/9/12-month return within the universe')}
      {cell('A/D', r.adGrade, 'Accumulation/distribution over 65 sessions')}
      {cell('Technical composite', r.techComposite, 'RS, A/D, group and off-high percentiles. Technical only: no EPS or SMR')}
      {cell('Off high', r.offHighPct === undefined ? undefined : `-${r.offHighPct.toFixed(1)} %`)}
      {cell('Off low', r.offLowPct === undefined ? undefined : `+${r.offLowPct.toFixed(1)} %`)}
      {cell('Vol vs 50d', signedPct(r.volVsAvg50Pct, 0))}
      {cell('Up/down vol', r.upDownVolRatio?.toFixed(2))}
      {cell('Turnover', r.avgTurnoverCr === undefined ? undefined : `₹${r.avgTurnoverCr.toFixed(0)} cr`)}
      {cell('Group', r.groupRank === undefined ? r.groupId : `${r.groupId} #${r.groupRank}`)}
    </div></Card>
  );
}

/** D1 candles with the 20/50/200-DMA and the open base: pivot, buy zone top, stop and goal as price lines, the base span and trigger as markers. */
function DailyChart({ candles, base }: { candles: Candle[]; base?: Base }) {
  const ref = useRef<HTMLDivElement>(null);
  const theme = useChartTheme();
  useEffect(() => {
    if (!ref.current || candles.length === 0) return;
    const chart = createChart(ref.current, { height: 380, width: ref.current.clientWidth, ...chartOptions(theme) });
    const day = (iso: string) => iso.slice(0, 10);
    const times = candles.map((c) => day(new Date(new Date(c.openTime).getTime() + 5.5 * 3600 * 1000).toISOString()));
    const series = chart.addCandlestickSeries({
      upColor: theme.profit, downColor: theme.loss, borderUpColor: theme.profit, borderDownColor: theme.loss, wickUpColor: theme.profit, wickDownColor: theme.loss,
    });
    series.setData(candles.map((c, i) => ({ time: times[i], open: Number(c.open), high: Number(c.high), low: Number(c.low), close: Number(c.close) })) as any);
    const closes = candles.map((c) => Number(c.close));
    for (const [period, color] of [[20, theme.warning], [50, theme.accent], [200, theme.series3]] as const) {
      const line = chart.addLineSeries({ color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false, title: `${period}-DMA` });
      line.setData(sma(closes, period).map((v, i) => (v === undefined ? null : { time: times[i], value: v })).filter(Boolean) as any);
    }
    if (base) {
      const priceLine = (price: number | string, color: string, title: string, style = 0) =>
        series.createPriceLine({ price: num(price), color, lineWidth: 1, lineStyle: style, axisLabelVisible: true, title });
      priceLine(base.pivot, theme.accent, 'pivot');
      priceLine(base.buyHigh, theme.accent, 'buy zone top', 2);
      priceLine(base.stop, theme.loss, 'stop', 2);
      priceLine(base.goal, theme.profit, 'goal', 2);
      const known = new Set(times);
      const markers = [
        { time: base.startDate, position: 'aboveBar', color: theme.muted, shape: 'arrowDown', text: `${base.type} start` },
        { time: base.detectedDate, position: 'belowBar', color: theme.muted, shape: 'circle', text: 'detected' },
        ...(base.triggerDate ? [{ time: base.triggerDate, position: 'belowBar', color: theme.accent, shape: 'arrowUp', text: base.volumeConfirmed ? 'trigger (volume)' : 'trigger' }] : []),
      ].filter((m) => known.has(m.time)).sort((a, b) => a.time.localeCompare(b.time));
      series.setMarkers(markers as any);
    }
    chart.timeScale().fitContent();
    return () => chart.remove();
  }, [candles, base, theme]);
  return <div ref={ref} data-testid="daily-chart" className="chart" />;
}

function ForwardPath({ o }: { o: AnalogOutcome }) {
  if (o.count === 0 || o.avgPath.length === 0) return null;
  const b = bandPoints(o, 260, 90);
  return (
    <div>
      <svg width={260} height={90} data-testid="forward-path" className="spark">
        <polygon points={b.band} className="spark-band" />
        <polyline points={b.average} className="spark-line" />
      </svg>
      <div className="muted text-sm">Average path and 25-75 band of {o.count} matches over {o.forward === 'close' ? 'the rest of the session' : `${o.forward} sessions`}
        {' '}({signedPct(b.min)} … {signedPct(b.max)})</div>
    </div>
  );
}

function OutcomeTable({ s }: { s: AnalogSummary }) {
  return (
    <div className="table-scroll"><table data-testid="analog-outcomes">
      <thead><tr><th>Forward</th><th>Higher</th><th>Median</th><th>P25 … P75</th><th>MAE / MFE (median)</th><th>Direction</th><th>Reliability</th><th>Risk</th><th>Spread</th></tr></thead>
      <tbody>
        {s.outcomes.map((o) => (
          <tr key={o.forward}>
            <td>{o.forward === 'close' ? '→ 15:10' : `${o.forward} sessions`}</td>
            <td>{rateWithCount(o.winRate, o.count)}</td>
            <td>{signedPct(o.median)}</td>
            <td>{signedPct(o.p25)} … {signedPct(o.p75)}</td>
            <td>{signedPct(o.maeMedian)} / {signedPct(o.mfeMedian)}</td>
            <td><Badge tone={directionTone(o.direction)}>{o.direction}</Badge></td>
            <td title={`${o.count} matches, ${o.distinctSymbols} symbols, ${o.distinctYears} years`}>{o.reliability}</td>
            <td>{o.risk}</td>
            <td>{o.consistency}{o.outlier ? ' · outlier' : ''}</td>
          </tr>))}
      </tbody>
    </table></div>
  );
}

function MatchTable({ matches, forwards }: { matches: AnalogMatch[]; forwards: string[] }) {
  const [key, setKey] = useState('similarity');
  const [descending, setDescending] = useState(false);
  const sorted = useMemo(() => sortMatches(matches, key, descending), [matches, key, descending]);
  const header = (k: string, label: string) => (
    <th aria-sort={key === k ? (descending ? 'descending' : 'ascending') : undefined}>
      <button type="button" className="dt-sort" onClick={() => { setDescending(key === k ? !descending : k !== 'similarity' && k !== 'symbol'); setKey(k); }}>
        {label}{key === k ? (descending ? ' ▼' : ' ▲') : ''}</button></th>);
  return (
    <div className="table-scroll"><table data-testid="analog-matches">
      <thead><tr>{header('symbol', 'Symbol')}{header('endDate', 'Window end')}{header('quality', 'Quality')}{header('similarity', 'Similarity')}
        {forwards.map((f) => header(f, f === 'close' ? '→ 15:10' : `+${f}`))}<th>Path</th></tr></thead>
      <tbody>
        {sorted.map((m) => (
          <tr key={`${m.symbol}-${m.endDate}`}>
            <td><Link to={`/stocks/${encodeURIComponent(m.symbol)}`}>{m.symbol}</Link></td><td>{m.endDate}</td><td>{m.quality.toFixed(1)}</td><td>{m.similarity.toFixed(2)}</td>
            {forwards.map((f) => <td key={f} className={`num tone-${(m.returns[f] ?? 0) > 0 ? 'profit' : (m.returns[f] ?? 0) < 0 ? 'loss' : 'neutral'}`}>{signedPct(m.returns[f])}</td>)}
            <td><svg width={80} height={20}><polyline className="spark-line spark-thin" points={pathPoints(m.path, 80, 20)} /></svg></td>
          </tr>))}
      </tbody>
    </table></div>
  );
}

function AnalogPanel({ symbol }: { symbol: string }) {
  const [lookback, setLookback] = useState(15);
  const [showMatches, setShowMatches] = useState(false);
  const path = encodeURIComponent(symbol);
  const { data, error } = useQuery({ queryKey: ['analogs', symbol, lookback], retry: false,
    queryFn: () => request<AnalogSummary>(`/analogs/${path}?lookback=${lookback}`) });
  const { data: matches } = useQuery({ queryKey: ['analog-matches', symbol, lookback], enabled: showMatches && !!data, retry: false,
    queryFn: () => request<AnalogMatch[]>(`/analogs/${path}/matches?lookback=${lookback}`) });
  const told = data?.outcomes.find((o) => o.forward === '5') ?? data?.outcomes[0];
  return (
    <Card data-testid="analog-panel" title={<>Historical analogs <Badge tone="warning" title={NOT_VALIDATED}>not validated</Badge></>}>
      <div className="stack">
      <div className="cluster" role="group" aria-label="lookback">
        {LOOKBACKS.map((l) => <Button key={l} size="sm" variant={l === lookback ? 'primary' : 'secondary'} aria-pressed={l === lookback} onClick={() => setLookback(l)}>{l}d</Button>)}
      </div>
      {error ? <Disabled what="Daily analogs" error={error} /> : !data ? <p>Loading…</p> : (
        <div className="stack">
          <p className="muted">Session {data.sessionDate} · {data.matches} matches from {data.compared.toLocaleString()} compared of {data.candidates.toLocaleString()} windows
            · match quality {data.qualityTag} ({data.medianQuality.toFixed(1)} of 5)</p>
          <div className="cluster analog-read">
            {told && <ForwardPath o={told} />}
            <ul data-testid="analog-read">{data.narrative.map((line, i) => <li key={i}>{line}</li>)}</ul>
          </div>
          <OutcomeTable s={data} />
          {data.splits.filter((x) => x.forward === '5').map((x) => (
            <p key={x.name} className="muted">Seasonality (5 sessions): same calendar month {rateWithCount(x.winRate, x.count)}, median {signedPct(x.median)}
              {' '}· other months {rateWithCount(x.otherWinRate, x.otherCount)}, median {signedPct(x.otherMedian)}</p>))}
          <div><Button onClick={() => setShowMatches(!showMatches)} aria-expanded={showMatches}>{showMatches ? 'Hide' : 'Show'} the {data.matches} matches</Button></div>
          {showMatches && matches && <MatchTable matches={matches} forwards={data.outcomes.map((o) => o.forward)} />}
        </div>)}
      </div>
    </Card>
  );
}

/** RS, technical composite and the latest session-analog line of a symbol, display only. Renders nothing without either. */
export function CandidateContextLine({ symbol, rating, session, link = true }: { symbol: string;
  rating?: { rsRating?: number | null; techComposite?: number | null }; session?: AnalogSummary; link?: boolean }) {
  if (!rating && !session) return null;
  return (
    <div data-testid="candidate-context" className="candidate-context" title={NOT_VALIDATED}>
      {rating ? <>RS {rating.rsRating ?? '—'} · composite {rating.techComposite ?? '—'}</> : null}
      {rating && session ? ' · ' : null}
      {session ? sessionLine(session.checkpoint, session.outcomes[0]) : null}
      {link ? <>{' '}<Link to={`/stocks/${encodeURIComponent(symbol)}`}>stock page</Link></> : null} <span className="tone-warning">(not validated)</span>
    </div>
  );
}

/** The context of one symbol (the stock page). */
function CandidateContext({ symbol }: { symbol: string }) {
  const path = encodeURIComponent(symbol);
  const { data: rating } = useQuery({ queryKey: ['rating', symbol], retry: false, queryFn: () => request<DailyRating>(`/ratings/${path}`) });
  const { data: session } = useQuery({ queryKey: ['session-analogs', symbol], retry: false, refetchInterval: 300_000,
    queryFn: () => request<AnalogSummary>(`/analogs/session/${path}`) });
  return <CandidateContextLine symbol={symbol} rating={rating} session={session} link={false} />;
}

/**
 * The context of all Today candidates in two requests, whatever their number: the API allows a burst of 40 requests per
 * principal, so a request per row would starve the rest of the page. Empty maps when a module is off.
 */
export function useCandidatesContext(symbols: string[]) {
  const unique = Array.from(new Set(symbols)).sort();
  const key = unique.join(',');
  const { data: screen } = useQuery({ queryKey: ['candidates-ratings', key], enabled: unique.length > 0, retry: false, refetchInterval: 600_000,
    queryFn: () => request<ScreenResult>('/ratings/screen', { method: 'POST', body: { filters: [{ field: 'symbol', op: 'in', value: unique }], limit: 200 } }) });
  const { data: sessions } = useQuery({ queryKey: ['candidates-session-analogs', key], enabled: unique.length > 0, retry: false, refetchInterval: 300_000,
    queryFn: () => request<Record<string, AnalogSummary>>(`/analogs/session?symbols=${unique.slice(0, 50).map(encodeURIComponent).join(',')}`) });
  const ratings: Record<string, { rsRating?: number | null; techComposite?: number | null }> = {};
  for (const row of screen?.rows ?? []) {
    ratings[String(row.symbol)] = { rsRating: row.rsRating as number | null, techComposite: row.techComposite as number | null };
  }
  return { ratings, sessions: sessions ?? {} };
}

export function Stock() {
  const { symbol: raw } = useParams();
  const symbol = decodeURIComponent(raw ?? '').toUpperCase();
  const path = encodeURIComponent(symbol);
  const client = useQueryClient();
  const { data: rating, error } = useQuery({ queryKey: ['rating', symbol], retry: false, queryFn: () => request<DailyRating>(`/ratings/${path}`) });
  const { data: bases } = useQuery({ queryKey: ['bases', symbol], retry: false, queryFn: () => request<Base[]>(`/ratings/${path}/bases`) });
  const { data: instrument } = useQuery({ queryKey: ['instrument', symbol], queryFn: () => request<Instrument>(`/instruments/resolve?symbol=${path}`) });
  const from = useMemo(() => new Date(Date.now() - 560 * 24 * 3600 * 1000).toISOString(), []);
  const { data: candles } = useQuery({ queryKey: ['d1', instrument?.id], enabled: !!instrument,
    queryFn: () => request<Candle[]>(`/market/candles?instrumentId=${instrument!.id}&timeframe=D1&from=${from}&to=${new Date().toISOString()}`) });
  const { data: watchlist } = useQuery({ queryKey: ['watchlist'], retry: false, queryFn: () => request<WatchlistItem[]>('/ratings/watchlist') });
  const watched = (watchlist ?? []).some((w) => w.symbol === symbol);
  const toggle = useMutation({
    mutationFn: () => (watched ? request(`/ratings/watchlist/${path}`, { method: 'DELETE' }) : request('/ratings/watchlist', { method: 'POST', body: { symbol } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['watchlist'] }) });
  const open = chartBase(bases ?? []);
  const past = (bases ?? []).filter((b) => isClosed(b.status));

  return (
    <Page
      title={<span className="stock-title">{symbol}{rating?.surveillance && <SurveillanceBadge {...rating.surveillance} />}</span>}
      actions={<>
        <Button data-testid="watch-toggle" onClick={() => toggle.mutate()} aria-pressed={watched}>{watched ? '★ Watching' : '☆ Watch'}</Button>
        <Link to="/screener">← Screener</Link>
      </>}
    >
      {rating && <p className="stock-quote"><span className="num">{String(rating.close)}</span> · <span className={`tone-${tone(rating.changePct)}`}>{signedPct(rating.changePct, 1)}</span> · session {rating.sessionDate}</p>}
      {error ? <Disabled what="Daily ratings" error={error} /> : rating ? <RatingsBlock r={rating} /> : <p>Loading…</p>}
      <Card title="Daily chart">
        {candles && candles.length > 0 ? <DailyChart candles={candles} base={open} /> : <p className="muted">No D1 candles stored for {symbol}.</p>}
        {open ? (
          <div data-testid="trade-plan" className="section">
            <b>{open.type.replace(/_/g, ' ')}</b> · {open.status.replace(/_/g, ' ')} since {open.statusDate} · depth {open.depthPct.toFixed(1)} % · detected {open.detectedDate}
            <div>Pivot <b>{String(open.pivot)}</b> · buy zone to <b>{String(open.buyHigh)}</b> · stop <b>{String(open.stop)}</b> · goal <b>{String(open.goal)}</b>
              {open.volumeConfirmed === true ? ' · breakout volume confirmed' : open.volumeConfirmed === false ? ' · breakout without volume' : ''}</div>
            <div className="muted text-sm">Informational: Hejje is an intraday system and does not trade this plan.</div>
          </div>) : <p className="muted section">No open base.</p>}
      </Card>
      <CandidateContext symbol={symbol} />
      <AnalogPanel symbol={symbol} />
      {past.length > 0 && (
        <Card title="Past setups" data-testid="past-setups">
          <div className="table-scroll">
            <table>
              <thead><tr><th>Type</th><th>Detected</th><th className="num">Pivot</th><th>Outcome</th><th>Closed</th><th className="num">Result</th></tr></thead>
              <tbody>{past.map((b) => (
                <tr key={b.id}><td>{b.type}</td><td>{b.detectedDate}</td><td className="num">{String(b.pivot)}</td><td>{b.status}</td><td>{b.statusDate}</td>
                  <td className="num">{b.outcomeR === undefined || b.outcomeR === null ? 'not triggered' : `${signedPct(b.outcomePct, 1)} (${b.outcomeR >= 0 ? '+' : ''}${b.outcomeR.toFixed(2)} R)`}</td></tr>))}
              </tbody>
            </table>
          </div>
        </Card>)}
    </Page>
  );
}
