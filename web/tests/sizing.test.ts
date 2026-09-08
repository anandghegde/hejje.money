import { describe, expect, it } from 'vitest';
import { sizeByRisk, formatPaise } from '../src/lib/sizing';

describe('sizeByRisk', () => {
  it('floors to lot size', () => {
    expect(sizeByRisk(24980, 24935, 10000, 75)).toBe(150);
    expect(sizeByRisk(24980, 24935, 2000, 75)).toBe(0);
    expect(sizeByRisk(100, 95, 1000, 1)).toBe(200);
  });
  it('respects maxQty', () => {
    expect(sizeByRisk(100, 95, 1000, 1, 150)).toBe(150);
  });
  it('rejects equal entry and stop', () => {
    expect(() => sizeByRisk(100, 100, 1000, 1)).toThrow();
  });
});

describe('formatPaise', () => {
  it('formats paise to rupees', () => {
    expect(formatPaise(9887)).toBe('98.87');
    expect(formatPaise(-1000000)).toContain('10,000');
  });
});
