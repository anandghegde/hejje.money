import { describe, expect, it } from 'vitest';
import { gttLabel, gttTone, price, riskUse, setupLabel, setupTone } from '../src/lib/swing';

describe('swing book helpers', () => {
  it('marks the GTT state with a word and a tone', () => {
    expect(gttTone('ACTIVE')).toBe('profit');
    expect(gttTone('MISSING')).toBe('loss');
    expect(gttTone('NONE')).toBe('warning');
    expect(gttLabel('MISSING')).toBe('✗ GTT missing');
    expect(gttLabel('ACTIVE')).toBe('✓ GTT active');
  });

  it('tones the watcher states', () => {
    expect(setupTone('TRIGGERED')).toBe('profit');
    expect(setupTone('NO_VOLUME')).toBe('warning');
    expect(setupTone('WAIT')).toBe('neutral');
    expect(setupLabel('ABOVE_BUY_ZONE')).toBe('above buy zone');
  });

  it('measures the overnight budget in use', () => {
    expect(riskUse(36180, 1000000)).toEqual({ pct: 3.6, tone: 'profit' });
    expect(riskUse(800000, 1000000)).toEqual({ pct: 80, tone: 'warning' });
    expect(riskUse(1100000, 1000000)).toEqual({ pct: 110, tone: 'loss' });
    expect(riskUse(5, 0)).toEqual({ pct: null, tone: 'neutral' });
  });

  it('writes prices from numbers or strings', () => {
    expect(price('100.5')).toBe('100.50');
    expect(price(93)).toBe('93.00');
    expect(price(null)).toBe('—');
  });
});
