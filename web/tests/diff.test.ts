import { describe, expect, it } from 'vitest';
import { lineDiff } from '../src/lib/diff';

describe('lineDiff', () => {
  it('marks added and removed lines around the common ones', () => {
    const before = 'name: orb\nentry:\n  all:\n    - close > vwap\nstop:\n  type: opening_range_low\n';
    const after = 'name: orb\nentry:\n  all:\n    - close > vwap\n    - rsi(14) > 50\nstop:\n  type: percent\n  value: 0.5\n';
    const d = lineDiff(before, after);
    expect(d.filter((l) => l.op === '+').map((l) => l.text)).toEqual(['    - rsi(14) > 50', '  type: percent', '  value: 0.5']);
    expect(d.filter((l) => l.op === '-').map((l) => l.text)).toEqual(['  type: opening_range_low']);
    expect(d.filter((l) => l.op === ' ').length).toBe(5);
  });
  it('is all context for identical texts', () => {
    expect(lineDiff('a\nb', 'a\nb')).toEqual([{ op: ' ', text: 'a' }, { op: ' ', text: 'b' }]);
  });
});
