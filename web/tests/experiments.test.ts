import { describe, expect, it } from 'vitest';
import { basis, canPromote } from '../src/lib/experiments';
import { ExperimentVariant, VariantMetrics } from '../src/api/types';

const split = (expectancyR: number) => ({ trades: 10, expectancyR, profitFactor: 1.2, maxDrawdownR: 3, winRate: 0.5, netPnl: 0 });

describe('experiments helpers', () => {
  it('ranks on out-of-sample, else validation, else overall', () => {
    const m = { overall: split(0.1), inSample: null, validation: split(0.2), outOfSample: split(0.3), walkForwardStdR: null, windows: 0, qualityWarnings: [] } as VariantMetrics;
    expect(basis(m)).toEqual({ name: 'OOS', split: split(0.3) });
    expect(basis({ ...m, outOfSample: null }).name).toBe('validation');
    expect(basis({ ...m, outOfSample: null, validation: null }).name).toBe('overall');
  });
  it('offers promotion only for finished, non-baseline, unpromoted variants', () => {
    const v = { ordinal: 1, status: 'DONE', promotedVersionId: null } as ExperimentVariant;
    expect(canPromote(v)).toBe(true);
    expect(canPromote({ ...v, ordinal: 0 })).toBe(false);
    expect(canPromote({ ...v, status: 'INVALID' })).toBe(false);
    expect(canPromote({ ...v, promotedVersionId: 'x' })).toBe(false);
  });
});
