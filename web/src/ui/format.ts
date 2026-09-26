/** The one sign and colour convention for numbers (plan Phase 10): profit, loss or neutral, always with a sign. */
export type Tone = 'profit' | 'loss' | 'neutral';

const MINUS = '−'; // U+2212, the same minus sign as lib/performance `rupees`

/** A number as a value: strings (REST prices are JSON strings) are parsed, anything unparsable is null. */
export function toNumber(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string' && v.trim() !== '') {
    const n = Number(v.replace(/[,\s₹%]/g, '').replace(MINUS, '-'));
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

export function tone(v: number | null | undefined): Tone {
  if (v === null || v === undefined || v === 0 || Number.isNaN(v)) return 'neutral';
  return v > 0 ? 'profit' : 'loss';
}

/** `+1.50`, `−0.25`, `0.00`; grouped the Indian way; `—` for no value. */
export function signed(v: number | null | undefined, digits = 2, suffix = ''): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  const abs = Math.abs(v).toLocaleString('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
  const zero = Number(abs.replace(/,/g, '')) === 0;
  const sign = zero ? '' : v > 0 ? '+' : MINUS;
  return `${sign}${abs}${suffix}`;
}
