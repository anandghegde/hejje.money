import { describe, expect, it } from 'vitest';
import { monthToDate, rupees, toggle } from '../src/lib/performance';

describe('performance helpers', () => {
  it('defaults to month to date', () => {
    expect(monthToDate(new Date(2026, 8, 10))).toEqual({ from: '2026-09-01', to: '2026-09-10' });
  });
  it('formats rupees with Indian grouping and a sign', () => {
    expect(rupees(-125000.5)).toBe('−₹1,25,000.50');
    expect(rupees(450)).toBe('₹450.00');
    expect(rupees(null)).toBe('—');
  });
  it('toggles selections', () => {
    expect(toggle(['A'], 'B')).toEqual(['A', 'B']);
    expect(toggle(['A', 'B'], 'A')).toEqual(['B']);
  });
});
