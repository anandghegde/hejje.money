/**
 * Strategy experiments (PRD 24, plan M4.7): variants of a base version, each defined by a delta, backtested with the same
 * engine and data, ranked deterministically (out-of-sample expectancy and profit factor, drawdown, trade count,
 * simplicity, walk-forward stability) with overfitting warnings. Nothing here changes a strategy's status; promoting a
 * variant creates a normal DRAFT version.
 */
@org.springframework.modulith.NamedInterface("experiments")
package money.hejje.backtest.experiments;
