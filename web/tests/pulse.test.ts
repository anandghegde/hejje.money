import { describe, expect, it } from 'vitest';
import { pulseColor, sectorBar, sparklinePoints } from '../src/lib/pulse';

describe('pulse helpers', () => {
  it('colours directions and sector labels', () => {
    expect(pulseColor('BULLISH')).toBe(pulseColor('STRONG'));
    expect(pulseColor('BEARISH')).toBe(pulseColor('WEAK'));
    expect(pulseColor('UNKNOWN')).not.toBe(pulseColor('NEUTRAL'));
  });
  it('sizes sector bars by relative change, capped at two points', () => {
    expect(sectorBar({ name: 'IT', symbol: 'x', label: 'UNKNOWN' })).toEqual({ width: 0, positive: true });
    expect(sectorBar({ name: 'Banking', symbol: 'x', label: 'STRONG', relativePct: 1 })).toEqual({ width: 50, positive: true });
    expect(sectorBar({ name: 'Metal', symbol: 'x', label: 'WEAK', relativePct: -3 })).toEqual({ width: 100, positive: false });
  });
  it('builds sparkline points across the width and scales values to the height', () => {
    expect(sparklinePoints([1])).toBe('');
    expect(sparklinePoints([1, 3, 2], 100, 10)).toBe('0.0,10.0 50.0,0.0 100.0,5.0');
  });
});
