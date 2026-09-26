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
strategy) gets a `trade_review` (idempotent per entry order). A round trip the signal engine still manages is skipped
there and reviewed from the engine's `StrategyPositionClosedEvent` instead, so a strategy trade's review always carries
its close reason (the two events of one exit fill reach their listeners in no fixed order):

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
  regime, event, news, exit reason, **cause** and **entry timing** (M9.6; `UNKNOWN` until classified), hour, instrument,
  and family × trend) carries its losses and share of all losses,
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

## Live-vs-backtest drift (Phase 5, M5.1)

`money.hejje.analytics.drift` compares each deployment's trailing paper/live trades with the version's base backtest
(PRD 25). PAPER and LIVE (CONFIRM/AUTO) deployments are separate: a deployment's trades are the reviewed strategy trades
(`trade_review` with a strategy position) of its own mode whose strategy position belongs to it.

- **Window**: the newest `trailing-trades` (30) trades closed within the last `trailing-sessions` (60) trading sessions;
  outcomes are the reviews' R multiples; a win is a trade with positive net P&L.
- **Baseline**: the base backtest (`BacktestService.baseBacktest`, the one the score uses); its out-of-sample slice when it
  has trades, else the whole run (stated in the evidence). No completed backtest → INSUFFICIENT_DATA.
- **Statistics**: one-sided binomial p-value of the live wins under the backtest win rate; a percentile bootstrap interval
  (2000 resamples, 90 %, fixed seed so the same trades give the same interval) of the live expectancy; expectancy ratio
  (live ÷ backtest, when the backtest's is positive); drawdown multiple (live max drawdown in R ÷ the backtest's).
- **Status**: fewer than `min-trades` (10) → INSUFFICIENT_DATA. Otherwise the levels in `config/drift.yaml` are checked
  FAILED, DEGRADING, WATCH and the first level with any met criterion is the status (else HEALTHY). Defaults: WATCH at
  p < 0.20, expectancy below 50 % of the backtest's, or drawdown ≥ 1×; DEGRADING at p < 0.05, the interval's upper bound
  below the backtest expectancy, or drawdown ≥ 1.5×; FAILED when the upper bound is below 0R (losing with confidence) or
  drawdown ≥ 2×. The criteria that fired are templated sentences (`triggered`).
- **Actions** (per status, `hejje.drift.actions`): `ALERT` (`/ws/events` `drift` message + WARN log; notification channels
  arrive with M5.5), `LOWER_SCORE` (the `Live-vs-backtest drift` score adjuster applies the status's points and the
  version is rescored), `REDUCE_SIZE` (deployment `size_multiplier` → 0.50; the risk per trade is multiplied by it),
  `MOVE_TO_PAPER` (a CONFIRM/AUTO deployment is paused and the version redeployed in PAPER on the same instruments,
  parameters and size at autonomy 0; skipped for PAPER deployments), `PAUSE` (`STRATEGY_PAUSED` audited with the
  statistics, actor SYSTEM). Actions run only when the status is worse than the worst status already acted on and the
  deployment is enabled, so a pause fires once; an improvement lowers that mark (a later relapse acts again). A size
  reduction is never undone automatically.
- **Override**: `POST /deployments/{id}/drift/override` with a reason suppresses actions for the current status and
  anything no worse and restores the size multiplier; a worse status acts again and ends the override, as does HEALTHY.
- **When**: after every reviewed strategy trade (its deployment) and every `hejje.drift.interval` (10 min) for enabled
  deployments. Status changes are audited (`STRATEGY_DRIFT_CHANGED` with the statistics and the actions) and kept in
  `drift_assessment`; the current state is `drift_state`.

Views: `GET /strategies/{id}/drift`, the strategy detail page's "Live vs backtest drift" panel (PRD 25 table, criteria,
history, Override…), and `hejje strategy <id>` (DRIFT section).

## Trade cause and entry timing (Phase 9, M9.6)

Every closed trade's review gets a **cause** and an **entry timing** from deterministic rules on the M1 candles of its
session (`TradeCauseClassifier`, thresholds in `config/analytics.yaml`, `hejje.analytics.cause.*`). R is the distance
from the entry to the initial stop; MFE and MAE are the best and worst moves while the trade was open, in R.

Causes, first match wins:

| Cause | Rule |
|---|---|
| `BAD_ENTRY` | at the entry, the move in the trade's direction over the previous `pre-entry-minutes` (15) was ≥ `extended-atr` (1.5) × ATR(14) of M1 (mean true range of the last 14 bars), or the entry was ≥ `vwap-atr` (2) ATR from the session VWAP in the trade's direction |
| `CLEAN_TARGET` | exited at the target (`TARGET`, which includes a bot's TAKE_PROFIT) with MAE better than `clean-target-mae-r` (−0.5R) |
| `NOISE_STOP` | stopped out (`STOP`, `TRAILING_STOP`, `SOFTWARE_STOP`), and within `post-exit-minutes` (30) after the exit the price regained the entry and reached `noise-recovery-r` (+1R) |
| `THESIS_BREAK` | exited by an exit rule (`RULE_EXIT`), a manual, bot or Jev exit (`MANUAL`), or stopped out without that recovery |
| `DRIFT` | a time or force exit (`MAX_HOLDING`, `FORCE_EXIT`) with abs(R) under `drift-r` (0.3) |
| `UNKNOWN` | anything else (e.g. a target reached after a deep MAE, a trade without a stop) |

Entry timing: `EARLY` when MAE reached `early-mae-r` (−0.7R) before MFE reached `early-mfe-r` (+0.5R); `LATE` when MFE
stayed under `late-mfe-r` (0.3R) and the entry was in the top (long) / bottom (short) `late-range-share` (20 %) of the
previous `range-minutes` (30) range; else `GOOD`.

**When.** A provisional cause is written with the review when the trade closes; a job (`TradeCauseJob#complete`, every
5 minutes; RUN in SIM on simulation time) completes it once `post-exit-minutes` + 5 have passed, or at the session
close. A trade closed less than 30 minutes before the close is completed from the candles that exist, with
`partialWindow: true` in the evidence. The evidence lists every number the rules used (`r`, `mfeR`, `maeR`,
`outcomeR`, `atr`, `preEntryMoveAtr`, `fromVwapAtr`, `postExitBestR`, `entryInRange`). The same trade and candles
always give the same answer.

**Jev beside the rules.** When Jev is on, completing a cause asks `config/jev/trade-cause.yaml` (`cause` choice,
`entry_timing` score over Early / Good / Late) about the trade in named buckets and stores `jevCause` / `jevTiming`.
The rules stay the source of truth. `GET /reviews/cause-agreement?from=&to=` (default the last 30 days) gives the
count, the cause and timing agreement rates and the confusion matrix (rules → Jev).

Surfaces: the review's `cause` object in `GET /reviews` and `/reviews/{id}`; `hejje reviews` (cause, timing, MFE/MAE,
Jev); the web Reviews page and review detail; the loss attribution's `cause` and `entryTiming` dimensions.

## Pace report (Phase 9, M9.7)

Does trading more each day hurt? `GET /analytics/pace` (and `hejje analytics pace [--from --to --mode --strategy]`)
takes the closed round trips of a period and reports, per bucket, the count, wins and win rate, expectancy in R (over
the trades with a stop, their count beside it) and in rupees net of costs, and the net P&L:

- by **trades taken that day**: 1–4, 5–8, 9–16, 17+ (the day's count of the trades in the report, so a strategy
  filter applies to it too);
- by the trade's **sequence number within its day**: 1st … 5th, 6th+;
- by **entry hour** (IST).

It describes; it changes nothing. The loss-streak allowance and the trades-per-day options (docs/risk.md) are the
controls to act on what it shows.

## Passive entries in the slippage report (Phase 9, M9.8)

`GET /analytics/slippage` adds `passive` when the period has passive (`limit_touch`) entry orders: how many were placed,
filled (fully or in part) and cancelled unfilled (`ENTRY_NOT_FILLED`), the fill rate (filled / decided), the mean
seconds from placement to the first fill, and the mean entry slippage against the signal's price for passive entries
and for market entries of strategies (with counts), so the two can be compared on the same period.
