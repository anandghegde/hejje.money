/**
 * Backtest module (PRD section 12, plan M2.3): a deterministic bar-replay engine that shares the indicator and rule
 * code with the live signal engine, PRD 12.1 metrics with visible costs, quality warnings, splits and walk-forward,
 * persistence and a bounded async runner. Also supplies the strategy lifecycle's {@code StrategyEvidence}.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.backtest;
