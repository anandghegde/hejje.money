# Recommendations, attribution and post-trade review (plan M2.7)

## Today screen and the recommendation engine (`money.hejje.recommend`)

PRD section 73 for the MVP: `score × signal validity × risk eligibility = recommendation`, no LLM.

For every enabled deployment × instrument in the current execution mode:

| Input | Source |
|---|---|
| Hejje Score | latest `strategy_score` row for the version on the instrument (null when never scored) |
| Signal validity | an unexpired ACTIVE/PREPARED signal from the runner (`SignalService.active()`) |
| Risk eligibility | the signal's dry run (`SignalService.dryRun`): sizing on the live quote plus the risk engine's checks, which include readiness, the kill switch and every PRD 31 control |

Decision: **AVOID** when a hard block stands (a failed risk check, a zero quantity); **TRADE** when a signal exists,
nothing blocks it and the score is at least `hejje.recommend.min-score` (70); **WAIT** otherwise (no signal yet, no
score, or a score below the threshold). `TRADE WITH CAUTION`, regime, breadth, news bias and event risk arrive with
the Phase 3 context engine (the fields exist as null / `UNKNOWN`).

Each recommendation carries the PRD 29 decision object (levels, quantity, risk and expected reward in rupees, signal
validity) and the PRD 20 explanation: supporting evidence (`✓` lines templated from the signal's evaluated conditions
with observed values, positive score adjustments, backtest expectancy and win rate, reward:risk at the current price)
and risks (`⚠` lines for negative adjustments, a capped base score, a reward:risk below 1, the reasons for WAIT).

`GET /today` returns the market header (watchlist quotes, VIX), Best Hejje (the top TRADE), the ranked list (TRADE
first, then by score) and the No-Trade message when nothing qualifies ("No strategy currently meets your minimum
quality threshold" or "No strategies deployed"). The `recommendation` table records a row whenever a signal's decision
or score changes (`GET /today/history?signalId=`).

## Attribution (`money.hejje.analytics`, PRD 53)

Every fill carries the strategy of the order's intent (`Trade.strategyId`); manual and imported orders count as
`MANUAL`. Positions are keyed per strategy, so a strategy's paper position never merges with a manual one.

Round trips are rebuilt from fills per (instrument, strategy): the entry side accumulates until the net quantity
returns to zero; entry and exit prices are volume-weighted averages, fees are the cost model's per-fill totals. A
round trip whose entry order came from a signal is linked to the strategy version and signal through
`strategy_position`.

`GET /analytics/pnl?groupBy=strategy|version|instrument|weekday|hour|regime` buckets the round trips of the range
(default: the last 30 days) with trades, wins, gross, fees, net, win rate and the average review R; `regime` is a
single `UNKNOWN` bucket until Phase 3. Bucket totals always sum to the summary.

## Post-trade review (PRD 55)

When a position goes flat (`PositionChangedEvent` with net 0) the latest closed round trip of that (instrument,
strategy) gets a `trade_review` (idempotent per entry order):

| Field | Strategy trade | Manual trade |
|---|---|---|
| `outcomeR` | net P&L ÷ (|entry − initial stop| × qty) | same, using the intent's stop; null without a stop |
| `expectedSetupValid` | entry rules re-evaluated on the last closed bar before entry from stored candles | same against the intent's selected strategy (latest version); null when none |
| `entrySlippageBps` | fill vs the signal's reference price (positive = worse) | null |
| `exitSlippageBps` | fill vs the planned level (stop for STOP/TRAILING/SOFTWARE_STOP, target for TARGET), 0 for market exits | null |
| `ruleAdherencePct` | 50 for a valid entry + 50 for a rule-driven exit | 50 when the selected strategy's entry rules held; null otherwise |
| `context` | `regime` (`trend × volatility` of the session, from the stored final label or the live snapshot when the trip closed today) and `breadth`; `news` / `event` = `UNKNOWN` until M3.3/M3.4 | same |

`GET /reviews`, `GET /reviews/{id}`, `GET /reviews/by-order/{entryOrderId}`; `POST /reviews/positions/{positionId}` (admin)
re-runs one.

## Development seeding (dev/test profiles only)

- `POST /market/dev/candles` (`hejje.market.dev-candles`): store and/or publish a scripted session and feed quotes.
- `POST /broker/dev/quote`: push a quote into the fake broker so market orders fill and stops trigger.
- `POST /strategies/{id}/versions/{v}/status` with `force: true` (`hejje.strategy.allow-forced-status`): skip the
  lifecycle evidence, audited as forced.
- `hejje.execution.allow-off-session-paper`: PAPER intents pass the session check outside market hours (dev only).

The Playwright paper flow (`web/tests-e2e/paper-flow.spec.ts`) uses exactly these: seed → signal → Today → execute →
fill → stop → review → attribution.
