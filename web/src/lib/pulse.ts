import { SectorStrength } from '../api/types';
import type { BadgeTone } from '../ui';

/** Tone of a pulse direction / sector label. */
export function pulseTone(label: string): BadgeTone {
  switch (label) {
    case 'BULLISH': case 'STRONG': return 'profit';
    case 'BEARISH': case 'WEAK': return 'loss';
    default: return 'neutral';
  }
}

/** Bar width (0..100) for a sector's relative change, ±2 percentage points = full width. */
export function sectorBar(s: SectorStrength): { width: number; positive: boolean } {
  if (s.relativePct == null) return { width: 0, positive: true };
  return { width: Math.min(100, Math.abs(s.relativePct) / 2 * 100), positive: s.relativePct >= 0 };
}

/** Points of an SVG polyline for a sparkline over a series of values. */
export function sparklinePoints(values: number[], width = 160, height = 40): string {
  if (values.length < 2) return '';
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  return values.map((v, i) => `${((i / (values.length - 1)) * width).toFixed(1)},${(height - ((v - min) / span) * height).toFixed(1)}`).join(' ');
}
