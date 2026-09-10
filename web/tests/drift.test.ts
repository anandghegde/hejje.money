import { describe, expect, it } from 'vitest';
import { canOverride, driftRows } from '../src/lib/drift';
import { DeploymentDrift } from '../src/api/types';

const drift = (status: DeploymentDrift['report']['status'], overrideStatus?: DeploymentDrift['report']['status']): DeploymentDrift => ({
  report: {
    deploymentId: 'd1', versionId: 'v1', version: 1, strategyId: 's1', mode: 'PAPER', enabled: true, sizeMultiplier: 0.5, status,
    window: { maxTrades: 30, sessions: 60, from: '2026-08-01', to: '2026-09-10' },
    live: { trades: 30, winRate: 0.47, expectancyR: -0.07, profitFactor: 0.9, maxDrawdownR: 5.25 },
    backtest: { trades: 60, winRate: 0.61, expectancyR: 0.28, profitFactor: 1.8, maxDrawdownR: 4, split: 'OUT_OF_SAMPLE' },
    triggered: [], evidence: [], assessedAt: '2026-09-10T10:00:00Z',
  },
  state: { deploymentId: 'd1', status, actedStatus: status, triggered: [], overrideStatus, updatedAt: '2026-09-10T10:00:00Z' },
  history: [],
});

describe('drift helpers', () => {
  it('lays out the PRD 25 comparison', () => {
    const rows = driftRows(drift('DEGRADING'));
    expect(rows.find((r) => r.metric === 'Win rate')).toEqual({ metric: 'Win rate', backtest: '61%', live: '47%' });
    expect(rows.find((r) => r.metric === 'Max drawdown')).toEqual({ metric: 'Max drawdown', backtest: '4.0R', live: '5.3R' });
    expect(rows.find((r) => r.metric === 'Expectancy')!.live).toContain('0.07');
    const noBacktest = { ...drift('INSUFFICIENT_DATA'), report: { ...drift('INSUFFICIENT_DATA').report, backtest: undefined } };
    expect(driftRows(noBacktest).every((r) => r.backtest === '—')).toBe(true);
  });
  it('offers an override for WATCH or worse once', () => {
    expect(canOverride(drift('DEGRADING'))).toBe(true);
    expect(canOverride(drift('DEGRADING', 'DEGRADING'))).toBe(false);
    expect(canOverride(drift('FAILED', 'DEGRADING'))).toBe(true);
    expect(canOverride(drift('HEALTHY'))).toBe(false);
    expect(canOverride({ ...drift('WATCH'), state: undefined })).toBe(false);
  });
});
