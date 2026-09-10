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

## Performance investigation (Phase 4, M4.5)

Trade reviews now record the context **as of the entry**: `event` (the instrument's event risk level then, with
`eventTrigger` naming the event) and `news` (the latest stored news-bias snapshot within the aggregation window before
the entry, with `newsScore`), next to the regime/breadth of the session. Missing sources are recorded as `UNKNOWN`.

`GET /analytics/pnl?groupBy=` gains `family`, `eventContext`, `newsBias` and `exitReason`.

`PerformanceMath` (pure, `money.hejje.analytics`) works on `TradeFact`s (a closed round trip plus its review context):

- **Loss attribution**: losses are the absolute net P&L of losing trades; every bucket (by family, strategy, trend,
  regime, event, news, exit reason, hour, instrument, and family × trend) carries its losses and share of all losses,
  sorted by losses. The headline is templated from the largest family × trend bucket ("83.3% of losses came from mean
  reversion strategies during strong up sessions").
- **Slippage**: entry and exit basis points (positive = worse for the trader): mean, median, p90 (nearest rank), worst,
  and cost ≈ bps × price × quantity, overall and per strategy.
- **Rule adherence**: mean adherence %, fully adherent trades, invalid setups, manual exits, and net P&L of fully vs
  partly adherent trades.
- **Counterfactual**: removes the trades matching **every** given category (AND across categories, OR within one) from
  the actual sequence and recomputes trades, net P&L, peak-to-trough drawdown of the cumulative net, win rate and profit
  factor. The result always has `basis: "SIMULATED"`, a note that it is hypothetical, and the actual figures next to the
  simulated ones (PRD 57). It does not model trades that might have been taken instead.

Endpoints (`market:read`, period defaults to month to date): `GET /analytics/losses`, `GET /analytics/slippage`,
`GET /analytics/adherence`, `POST /analytics/counterfactual` (`{ "from", "to", "exclude": { "families": [...], "trends": [...],
"regimes", "strategies", "events", "news", "hours", "instruments" } }`). Agent tools: `get_loss_attribution`,
`get_slippage_stats`, `get_rule_adherence`, `run_counterfactual`; Hejje AI's `losses` flow ("What lost me money this
month?") composes them and keeps ACTUAL and SIMULATED evidence apart. The web Analytics page has a "Loss investigation"
section with a counterfactual panel (actual and simulated columns side by side).
