import { DeploymentDrift, DriftStatus } from '../api/types';
import { formatR } from './today';
import type { BadgeTone } from '../ui';

export const DRIFT_TONE: Record<DriftStatus, BadgeTone> = {
  INSUFFICIENT_DATA: 'neutral', HEALTHY: 'profit', WATCH: 'warning', DEGRADING: 'warning', FAILED: 'loss',
};

const pct = (x?: number) => (x == null ? '—' : `${Math.round(x * 100)}%`);
const r = (x?: number) => (x == null ? '—' : formatR(x));

/** The PRD 25 comparison: each metric for the backtest (out-of-sample slice when it has one) and the trailing paper/live trades. */
export function driftRows(d: DeploymentDrift): { metric: string; backtest: string; live: string }[] {
  const { live, backtest } = d.report;
  return [
    { metric: 'Trades', backtest: backtest ? String(backtest.trades) : '—', live: String(live.trades) },
    { metric: 'Win rate', backtest: pct(backtest?.winRate), live: pct(live.winRate) },
    { metric: 'Expectancy', backtest: r(backtest?.expectancyR), live: r(live.expectancyR) },
    { metric: 'Profit factor', backtest: backtest?.profitFactor?.toFixed(2) ?? '—', live: live.profitFactor?.toFixed(2) ?? '—' },
    { metric: 'Max drawdown', backtest: backtest ? `${backtest.maxDrawdownR.toFixed(1)}R` : '—', live: `${live.maxDrawdownR.toFixed(1)}R` },
  ];
}

/** A manual override is offered for a stored status of WATCH or worse that is not already overridden. */
export function canOverride(d: DeploymentDrift): boolean {
  const s = d.state;
  return !!s && ['WATCH', 'DEGRADING', 'FAILED'].includes(s.status) && s.overrideStatus !== s.status;
}
