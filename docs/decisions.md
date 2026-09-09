# Decision states and the Context Card (PRD §15, §19, §20 — plan M3.5)

Every deployed strategy × instrument gets one of four states on Today, computed by `RecommendationService` from the
signal engine, the risk pipeline, the Hejje Score and the context services. The table is implemented in
`DecisionRules` (pure) and applied in this order:

| Rule | State |
|---|---|
| A hard block stands: any failing risk control (kill switch, readiness, daily loss, sizing, reward:risk below the global minimum, …), a strategy `event_rules: block` on a HIGH-risk event, or an unsizeable signal | `AVOID` |
| No active (unexpired) signal, or the only failing control is the trading window (`tradingWindow`: after the no-new-trades cutoff) | `WAIT` |
| Signal, no hard block, but no Hejje Score yet or a score below `hejje.recommend.min-score` (70) | `WAIT` |
| Score at or above the threshold and no caution | `TRADE` |
| Score at or above the threshold with at least one caution | `TRADE_WITH_CAUTION` |

A paused deployment is not ranked at all (only enabled deployments in the current mode are evaluated).

## Cautions

Each caution is a structured `{code, message}` on the recommendation (`cautions[]`) and a "⚠" line under risks:

| Code | When | Config |
|---|---|---|
| `EVENT_CAUTION` | the strategy's `event_rules: caution` triggers (HIGH-risk event within its window) | docs/events.md |
| `VIX_RISING` | INDIA VIX is up more than `caution.vix-rise-pct` on the day | `hejje.recommend.caution.vix-rise-pct` (5 %) |
| `REWARD_RISK` | reward:risk at the current price is below the strategy's `risk_overrides.min_reward_risk` but at or above the global `min_reward_risk` (below the global one is a hard block) | strategy definition, risk limits |
| `REGIME_AVOID` | a `regime_preferences: avoid` key matches the current regime | docs/regime.md |
| `NEWS_OPPOSING` | the news bias opposes the signal's direction at or beyond `caution.news-opposing-score` (long with bias ≤ −0.4, short with bias ≥ +0.4) | `hejje.recommend.caution.news-opposing-score` (0.4) |
| `MARKET_DATA_STALE` | the instrument's last quote is missing or flagged stale by the quote cache (`hejje.market.quote-stale-after`, 5 s); older than `hejje.market.stale-after` (10 s) is already a readiness hard block | `hejje.market.quote-stale-after` |

Cautions never block: execution of a `TRADE_WITH_CAUTION` recommendation goes through the same prepare → confirm
flow, and the risk pipeline is unchanged.

## "Why this trade?" (PRD 20)

`supportingEvidence[]` and `risks[]` are templated from structured evidence only:

- **Supporting**: the signal's entry conditions with observed values ("✓ close > opening_range_high (24930.00 vs
  24905.00)"), the reward:risk at the current price, positive score adjusters ("✓ Current regime +6"), the backtest
  expectancy and win rate, and the Context Card's GREEN rows ("✓ Market breadth positive", "✓ Banking sector
  outperforming", "✓ Strategy performs strongly in the current regime: 0.42R vs 0.25R overall").
- **Risks**: every caution, negative adjusters ("⚠ Event risk −8"), the base-score cap, and RED context rows.
- **Hard blocks** are listed separately (`hardBlocks[]`).

No sentence is generated freely; every line maps to a rule above or an adjuster's evidence.

## The Context Card (PRD 19)

`GET /api/v1/context/strategy?versionId=&instrumentId=` (and `recommendation.context`) returns five rows, each
`{name, status GREEN|AMBER|RED|UNKNOWN, value, delta, evidence[]}`:

| Row | Source | GREEN / AMBER / RED |
|---|---|---|
| Technical fit | "Technical compatibility" adjuster (−10..+8) | top third of the range / middle / bottom third |
| Market regime | "Current regime" adjuster (−10..+10) | same thirds; "Favorable / Neutral / Unfavorable" |
| News bias | news bias (docs/news.md) | score ≥ +0.2 / between / ≤ −0.2; value "Bullish +0.4" |
| Event risk | event risk (docs/events.md) | LOW / MEDIUM / HIGH |
| Sector | the instrument's sector (config/aliases.yaml) in the Market Pulse sector rows | STRONG / NEUTRAL / WEAK |

`netImpact` is the sum of the regime, event and news deltas (the score points context added or removed);
`nextEvent` is the "Q2 Results — Today 16:00" line. A row is `UNKNOWN` (value "Unknown", delta null, reason in
evidence) whenever its service is disabled, stale, or fails — the card and Today still render, and execution is
unaffected because none of these rows feed the risk pipeline.
