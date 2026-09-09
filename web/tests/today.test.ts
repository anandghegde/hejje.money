import { describe, expect, it } from 'vitest';
import { decisionColor, formatR, rewardRisk, secondsLeft } from '../src/lib/today';

describe('today helpers', () => {
  it('computes reward:risk from levels', () => {
    expect(rewardRisk({ entry: 1507, stop: 1495, target: 1531 })).toBe(2);
    expect(rewardRisk({ entry: 1507, stop: 1507, target: 1531 })).toBeNull();
    expect(rewardRisk({ entry: 1507, stop: 1495 })).toBeNull();
  });
  it('formats R multiples and decision colours', () => {
    expect(formatR(0.4213)).toBe('+0.42R');
    expect(formatR(-1)).toBe('-1.00R');
    expect(formatR(null)).toBe('—');
    expect(decisionColor('TRADE')).not.toBe(decisionColor('AVOID'));
  });
  it('counts remaining validity without going negative', () => {
    const now = new Date('2026-09-08T04:05:00Z');
    expect(secondsLeft('2026-09-08T04:10:00Z', now)).toBe(300);
    expect(secondsLeft('2026-09-08T04:00:00Z', now)).toBe(0);
    expect(secondsLeft(undefined, now)).toBe(0);
  });
});
