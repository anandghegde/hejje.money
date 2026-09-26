import type { BadgeTone } from '../ui';
import { toNumber } from '../ui';

/** Plan M11.6: the swing book as the API returns it (docs/api.md, "Swing book"). Prices are JSON numbers or strings. */
export interface SwingBookRow {
  id: string; instrumentId: string; symbol: string; strategyId?: string | null; openedAt: string; entryDate: string; daysHeld: number;
  quantity: number; entryPrice: number | string; stop?: number | string | null; goal?: number | string | null; lastPrice: number | string;
  r?: number | string | null; unrealizedPnl: { paise: number }; gtt: 'ACTIVE' | 'MISSING' | 'NONE' | string; gttId?: string | null;
}

export interface SwingSetup {
  deploymentId: string; baseId: string; instrumentId: string; symbol: string; type: string; pivot: number | string; buyHigh: number | string;
  stop: number | string; goal: number | string; avgVolume50?: number | null; state: string; lastClose?: number | string | null;
  pace?: number | string | null; signalId?: string | null;
}

export interface SwingPositionRisk {
  instrumentId: string; symbol: string; quantity: number; price: number | string; stop?: number | string | null; industry?: string | null;
  risk: { paise: number };
}

export interface SwingRisk {
  mode: string; overnightRisk: { paise: number }; budget: { paise: number }; gapAllowancePct: number | string; openPositions: number;
  maxOpenPositions: number; deployed: { paise: number }; capital: { paise: number }; positions: SwingPositionRisk[];
}

/** A missing broker-side stop is an incident; no GTT yet (the seconds after a fill) a warning. */
export function gttTone(state: string): BadgeTone {
  return state === 'ACTIVE' ? 'profit' : state === 'MISSING' ? 'loss' : 'warning';
}

/** The GTT state with a mark, so colour is never the only signal. */
export function gttLabel(state: string): string {
  return state === 'ACTIVE' ? '✓ GTT active' : state === 'MISSING' ? '✗ GTT missing' : '⚠ no GTT';
}

/** The watcher's state of a setup (docs/swing.md): triggered is the news, the refusals are warnings. */
export function setupTone(state: string): BadgeTone {
  switch (state) {
    case 'TRIGGERED': return 'profit';
    case 'ABOVE_BUY_ZONE': case 'NO_VOLUME': case 'STOP_TOO_NEAR': return 'warning';
    default: return 'neutral';
  }
}

export function setupLabel(state: string): string {
  return state.toLowerCase().replace(/_/g, ' ');
}

/** The share of the overnight budget in use, and its tone: over budget is a loss, three quarters a warning. */
export function riskUse(used: number, budget: number): { pct: number | null; tone: BadgeTone } {
  if (!budget || budget <= 0) return { pct: null, tone: 'neutral' };
  const pct = Math.round((1000 * used) / budget) / 10;
  return { pct, tone: pct > 100 ? 'loss' : pct >= 75 ? 'warning' : 'profit' };
}

/** A price for display: two decimals, `—` for none. */
export function price(v: unknown): string {
  const n = toNumber(v);
  return n === null ? '—' : n.toFixed(2);
}
