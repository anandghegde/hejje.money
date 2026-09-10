/**
 * Live-vs-backtest drift (PRD section 25, plan M5.1): per deployment, the trailing paper/live trades (outcomes in R from
 * the post-trade reviews) against the version's out-of-sample backtest, with a binomial test on the win rate, a bootstrap
 * confidence interval on the expectancy and the drawdown multiple. Statuses and the actions each status takes (alert,
 * lower score, reduce size, move to paper, pause) come from config/drift.yaml; actions run once per escalation and a
 * manual override with a reason suppresses them. Rules: docs/analytics.md, "Live-vs-backtest drift".
 */
@org.springframework.modulith.NamedInterface("drift")
package money.hejje.analytics.drift;
