import { AnalogMatch, AnalogOutcome, Base, ScreenFilter } from '../api/types';
import type { BadgeTone } from '../ui';

/** Daily context helpers (plan M8.7). Pure functions so they can be unit tested. */

export const LISTS = ['setups', 'buyzone', 'nearpivot', 'leaders', 'movers', 'groups'] as const;
export const LIST_LABEL: Record<string, string> = {
  setups: 'Setups', buyzone: 'In buy zone', nearpivot: 'Near pivot', leaders: 'Leaders', movers: 'On the move', groups: 'Top groups',
};
export const LOOKBACKS = [5, 10, 15, 20, 25, 30, 40, 50];
export const OPS: ScreenFilter['op'][] = ['gte', 'lte', 'gt', 'lt', 'eq', 'ne', 'in'];

export function conditionTone(condition?: string): BadgeTone {
  switch (condition) {
    case 'CONFIRMED_UPTREND': case 'Confirmed uptrend': return 'profit';
    case 'UPTREND_UNDER_PRESSURE': case 'Uptrend under pressure': return 'warning';
    case 'RALLY_ATTEMPT': case 'Rally attempt': return 'info';
    case 'DOWNTREND': case 'Downtrend': return 'loss';
    default: return 'neutral';
  }
}

export function directionTone(direction?: string): BadgeTone {
  if (direction?.startsWith('BULLISH')) return 'profit';
  if (direction?.startsWith('BEARISH')) return 'loss';
  return 'neutral';
}

/** A rate is never shown without its count: "27 of 40 (68 %)". */
export function rateWithCount(winRate: number, count: number): string {
  if (count === 0) return 'no matches';
  return `${Math.round(winRate * count)} of ${count} (${Math.round(winRate * 100)} %)`;
}

export function signedPct(v?: number | null, digits = 2): string {
  if (v === undefined || v === null || Number.isNaN(v)) return '—';
  return `${v >= 0 ? '+' : ''}${v.toFixed(digits)} %`;
}

export function num(v: number | string | undefined | null): number {
  return v === undefined || v === null ? NaN : Number(v);
}

/** A filter typed into the builder: the value becomes a number when it looks like one, a list for `in`. */
export function buildFilter(field: string, op: ScreenFilter['op'], raw: string): ScreenFilter | null {
  const text = raw.trim();
  if (!field || text === '') return null;
  if (op === 'in') return { field, op, value: text.split(',').map((s) => s.trim()).filter(Boolean) };
  const n = Number(text);
  return { field, op, value: Number.isFinite(n) ? n : text };
}

export function describeFilter(f: ScreenFilter): string {
  const symbol: Record<string, string> = { gte: '≥', lte: '≤', gt: '>', lt: '<', eq: '=', ne: '≠', in: 'in' };
  return `${f.field} ${symbol[f.op]} ${Array.isArray(f.value) ? f.value.join(', ') : f.value}`;
}

/** The badge text of an NSE surveillance flag ("ASM LT 2", "GSM 0"); null when the stock is not under surveillance or the flag is unknown. */
export function surveillanceLabel(flag?: string | null): string | null {
  const m = /^(ASM_LT|ASM_ST|GSM)_(\d+)$/.exec(flag ?? '');
  return m ? `${m[1].replace('_', ' ')} ${m[2]}` : null;
}

export function isClosed(status: string): boolean {
  return ['HIT_GOAL', 'STOPPED', 'FAILED', 'EXPIRED'].includes(status);
}

/** The base drawn on the chart: the open base before an open reversal setup; none when everything is closed. */
export function chartBase(bases: Base[]): Base | undefined {
  const open = bases.filter((b) => !isClosed(b.status));
  return open.find((b) => b.type !== 'MA_REVERSAL') ?? open[0];
}

/** Simple moving average aligned with the closes (undefined until the window is full). */
export function sma(closes: number[], period: number): (number | undefined)[] {
  const out: (number | undefined)[] = [];
  let sum = 0;
  for (let i = 0; i < closes.length; i++) {
    sum += closes[i];
    if (i >= period) sum -= closes[i - period];
    out.push(i >= period - 1 ? sum / period : undefined);
  }
  return out;
}

export type MatchSort = 'similarity' | 'quality' | 'endDate' | 'symbol' | string;

/** The API returns matches unordered on purpose; the table sorts them here. A key that is not a column sorts by that forward return. */
export function sortMatches(matches: AnalogMatch[], key: MatchSort, descending: boolean): AnalogMatch[] {
  const value = (m: AnalogMatch): number | string => {
    switch (key) {
      case 'similarity': return m.similarity;
      case 'quality': return m.quality;
      case 'endDate': return m.endDate;
      case 'symbol': return m.symbol;
      default: return m.returns[key] ?? Number.NEGATIVE_INFINITY;
    }
  };
  const sorted = [...matches].sort((a, b) => {
    const x = value(a);
    const y = value(b);
    return x < y ? -1 : x > y ? 1 : a.symbol.localeCompare(b.symbol);
  });
  return descending ? sorted.reverse() : sorted;
}

/** SVG polyline points of a path inside a width x height box (a match sparkline, the average forward path). */
export function pathPoints(values: number[], width: number, height: number, min?: number, max?: number): string {
  if (values.length < 2) return '';
  const lo = min ?? Math.min(...values);
  const hi = max ?? Math.max(...values);
  const span = hi - lo || 1;
  return values.map((v, i) => `${((i / (values.length - 1)) * width).toFixed(1)},${(height - ((v - lo) / span) * height).toFixed(1)}`).join(' ');
}

/** The 25-75 band as a closed SVG polygon, from the start (0 %) through the upper path and back along the lower one. */
export function bandPoints(o: AnalogOutcome, width: number, height: number): { band: string; average: string; min: number; max: number } {
  const upper = [0, ...o.p75Path];
  const lower = [0, ...o.p25Path];
  const average = [0, ...o.avgPath];
  const min = Math.min(...lower, ...average);
  const max = Math.max(...upper, ...average);
  const up = pathPoints(upper, width, height, min, max);
  const down = pathPoints(lower, width, height, min, max).split(' ').reverse().join(' ');
  return { band: `${up} ${down}`, average: pathPoints(average, width, height, min, max), min, max };
}

/** The one-line session-analog summary shown per candidate on Today. */
export function sessionLine(checkpoint: string, o?: AnalogOutcome): string {
  if (!o || o.count === 0) return `Session analogs ${checkpoint}: no matches`;
  if (o.direction === 'INSUFFICIENT') return `Session analogs ${checkpoint}: ${o.count} matches, too few to read`;
  return `Session analogs ${checkpoint}: ${o.direction.replace('_', ' ').toLowerCase()}, higher ${rateWithCount(o.winRate, o.count)}, median ${signedPct(o.median)} to 15:10`;
}
