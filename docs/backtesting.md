# Backtesting (`money.hejje.backtest`)

A deterministic bar-replay backtester (PRD section 12). It shares the indicator library and the condition evaluator
with the live signal engine, so the same candles and the same strategy version always produce the same trades
(README rule 10); `resultHash` (SHA-256 over the trade list) makes that checkable.

## Spec

`POST /api/v1/backtests` takes a version (`versionId` or `strategyId` + `version`), instruments (Hejje symbols;
empty = the definition's resolved universe), an optional timeframe (default: the definition's), `from`/`to` session
dates (IST, inclusive), `fillModel` (`NEXT_OPEN` default, `BAR_CLOSE` for parity runs), `slippageBps` (default 5),
`splits`, `initialCapitalRupees` (default 10,00,000) and `riskPerTradeRupees` (default: the definition's
`position_sizing.risk_rupees`, else `risk_percent_of_capital` × capital, else `hejje.backtest.default-risk-rupees`).

Candles come from `MarketService.candles` (Postgres recent + Parquet history) from `from − hejje.backtest.warmup-days`
so indicators are ready on the first session; bars before `from` never trade.

## Replay rules (per instrument, per bar, in this order)

1. **Pending entry fills at the open** of the bar after the signal bar (`NEXT_OPEN`), buy side slipped up / sell
   side slipped down by `slippageBps` and rounded to the tick (buys up, sells down). With `BAR_CLOSE` the entry fills
   at the signal bar's close. Quantity = `PositionSizer.size(entry, stop, riskPerTrade, lotSize, risk_overrides.max_quantity)`;
   a zero quantity or a stop on the wrong side of the fill (gap) skips the signal (`skippedSignals`).
2. **Intrabar stop/target** against the bar's high and low. If both are touched in one bar the stop wins
   (conservative rule). A stop fills at the stop price, or at the open when the bar gaps through it, then slippage
   (stop-market). A target fills at the target price (limit, no slippage).
3. **The bar closes**: the indicator context sees it.
4. **Close-of-bar management** of an open trade: trailing stop (`atr_multiple`, `percent`, `breakeven_at_r`) moves
   the stop only in the trade's favour; a `vwap` target follows the session VWAP; `exit` rules (evaluated from the bar
   after entry) exit at the close with slippage (`RULE_EXIT`); `max_holding_minutes` (`MAX_HOLDING`); the bar closing
   at or after `force_exit_time` exits at its close (`FORCE_EXIT`).
5. **Entry evaluation when flat**, for bars closing inside `trade_window` and while `max_trades_per_day` (per
   instrument) is not reached. Every entry condition is evaluated and recorded as evidence
   (`{condition, status, lhs, rhs}`); `NOT_READY` never passes.

Stops at the signal bar: `opening_range_low/high` (15-minute range), `atr_multiple` (`atr(14)`), `percent`, `points`,
`swing_low/high` (`lowest(10)` / `highest(10)`), `prev_day_low/high`. Long stops round down to the tick, short stops
up. Targets: `risk_multiple` × |entry − stop|, `points`, `percent`, `vwap`, `none`.

Open trades are closed at the last bar of the data (`END_OF_DATA`). `direction: both` is not supported yet (a single
`entry` block cannot pick a side).

## Costs and P&L

Every fill is priced by the shared `CostModel` (`config/costs.yaml`): brokerage, STT, exchange transaction charges,
GST, SEBI fee, stamp duty. `grossPnl`, `costs` and `netPnl` are stored per trade and summed in the metrics, so
gross vs cost vs net is always visible (PRD 12.2). `rMultiple = netPnl / (|entry − initial stop| × qty)`.

## Splits (PRD 12.4)

- `NONE`: everything in-sample.
- `FIXED` (default 60/20/20): sessions in order → in-sample, validation, out-of-sample.
- `WALK_FORWARD {trainMonths, testMonths, anchored}`: rolling windows; sessions inside any test window are
  out-of-sample. Each window's out-of-sample trade count and expectancy is recorded (`windows[]`) for the score's
  stability component (M2.5).

Metrics are computed overall and per split (`bySplit`).

## Metrics (PRD 12.1)

Total/winning/losing trades, win and loss rate, average win/loss, win/loss ratio, expectancy (R and money), profit
factor (null with no losses), total return %, CAGR (null under one year), Sharpe and Sortino (daily net P&L over
capital across every session in range, annualised by √252; null when undefined), max drawdown (money, R, % of
capital) and its duration in days, consecutive wins/losses, average holding minutes, largest win/loss, gross/costs/net,
R-multiple distribution (lower-inclusive buckets), monthly / day-of-week / hour-of-entry tables, equity and drawdown
curves (per closed trade).

## Quality warnings (PRD 12.3)

| Code | Severity | Rule |
|---|---|---|
| `INSUFFICIENT_SAMPLE` | FAIL | fewer than 30 trades (blocks VALIDATED) |
| `LOW_SAMPLE` | WARN | fewer than 100 trades |
| `CONCENTRATED` | WARN | top 5 trades > 50 % of net profit |
| `MISSING_DATA` | WARN | more than 2 % of expected trading sessions have no candles |
| `OVERFIT_RISK` | WARN | fewer than 10 trades per tunable parameter (numeric literals, indicator arguments, stop/target/trailing values) |
| `UNREALISTIC_FILLS` | WARN | more than 10 % of entries filled at the bar's high or low |
| `ZERO_VOLUME_BARS` | WARN | entries on zero-volume bars of a tradable instrument |
| `IS_OOS_GAP` | WARN | out-of-sample expectancy below half of in-sample |
| `PASSIVE_ENTRY_NOT_FILLED` | WARN | the definition enters with `limit_touch` (plan M9.8): the counts of passive entries that filled and that did not before their cancel time (`evidence.filled`, `evidence.notFilled`) |
| `MICROSTRUCTURE_NOT_READY` | WARN | the rules use an order-book or flow indicator (`book_imbalance`, `book_imbalance_mean`, `buy_sell_qty_ratio`, `flow_up_share`); candle history has no such data, so those conditions never pass in the backtest (plan M9.4) |

## Lifecycle evidence

The backtest module supplies `StrategyEvidence`: `DRAFT → BACKTESTED` needs a `DONE` backtest for the version;
`BACKTESTED → VALIDATED` needs a `DONE` backtest whose split scheme has an out-of-sample slice with at least 30
trades and no FAIL warning. A run with a research-only `sessionFilter` (below) never counts as evidence.

## Session filter (research only, plan M8.8)

`BacktestSpec.sessionFilter` restricts entries per session date to a set of instruments and sides, supplied from
outside the strategy by a validation script (the previous session's RS leaders, the previous session's market
condition). In the replay it is checked when an entry would be evaluated, after the trade window and the daily trade
cap and before the entry rules, so a blocked bar costs no trade of the day; stops, targets and force exits run
unchanged. It only ever removes entries, so a filtered run's trades are a subset of what the rules allowed. Because the
restriction is not part of the strategy definition, such a run is excluded from lifecycle evidence, from the base
backtest the Hejje Score reads and from the agent's `get_strategy_backtest`. If a filter proves useful it becomes a real
DSL feature with replay parity (plan M8.9); until then it is a measuring instrument.

## Running

Backtests run on a bounded pool (`hejje.backtest.workers`, default 2) with progress (`progressPct`) and cancellation
(`DELETE /backtests/{id}` cancels a queued/running run and deletes a finished one). Runs left `QUEUED`/`RUNNING` by a
restart are marked `FAILED` at startup.

## Experiments (Phase 4, M4.7)

`backtest.experiments` tests variants of a base version on one dataset and split:

- **Variants** are deltas applied to the base YAML as a JSON merge patch (nested objects merge, lists and scalars replace,
  `null` removes a key) plus `entry_add` / `exit_add` to append conditions. A delta cannot rename the strategy. Each delta
  is validated like any definition; invalid ones are kept as `INVALID` with their errors and not run. The baseline (the
  base version itself) is always variant 0.
- **Data**: the base version's judged backtest (dataset, instruments, split, slippage) unless the request says otherwise;
  otherwise the last year on the strategy's universe with the default fixed split.
- **Runs**: `BacktestService.evaluate(spec, definition)` backtests an unsaved definition with the same engine, data and
  costs; variants run on a bounded pool (`hejje.backtest.experiments.parallelism`, default 2). Experiments interrupted by a
  restart are marked FAILED.
- **Ranking** (`ExperimentRanking`, deterministic): each criterion ranks the variants from 1 (best) to 0 (worst, ties
  share); the score is the weighted mean × 100 over the criteria every variant has — out-of-sample expectancy (0.35; falls
  back to validation, then overall), out-of-sample profit factor (0.15), max drawdown in R (0.15, lower is better), trade
  count capped at 100 (0.10), simplicity = parameters + conditions (0.15, fewer is better), walk-forward stability =
  std-dev of window expectancy (0.10, lower is better).
- **Warnings**: `IS_OOS_GAP` (in-sample expectancy exceeds out-of-sample by more than max(0.2R, half the in-sample
  figure)), `LOW_TRADES` (< 100 trades), `PARAMETER_AT_EDGE` (a parameter swept over three or more values sits at the
  low or high end of the tested range), FAIL-level backtest quality warnings; experiment notes `MULTIPLE_COMPARISONS`
  (three or more variants) and `NO_OUT_OF_SAMPLE`.
- **Verdicts**: `BASELINE`; `NOT_BETTER` (no better out-of-sample expectancy than the baseline); `BETTER_BUT_FRAGILE`
  (better but warned); the best-ranked clean improvement is `RECOMMENDED`, others `BETTER_OUT_OF_SAMPLE`.
- **Promotion** (`POST /experiments/{id}/variants/{variantId}/promote`) creates the strategy's next version as a DRAFT with
  change note "promoted from experiment … variant …". Nothing in an experiment changes a status.

Agent tools: `propose_variants` (the LLM, profile `research`, prompt `experiment_agent_v1`, proposes deltas preferring
out-of-sample robustness and simplicity; each is validated), `run_experiment`, `get_experiment`. Web Lab lists the
experiments of the latest version with ranks, verdicts and warnings and offers "Promote to version"; TUI
`hejje experiments [id]` is read-only.

## Passive entries (plan M9.8)

With `entry_order: { type: limit_touch }` the backtester does not know the touch (candles have no bid/ask), so a
signal places a limit at the **signal bar's close**, rounded away from a fill (down for a long, up for a short). It
fills **at the limit, with no slippage**, on the first later bar that trades **strictly through** it (a long needs a
low below the limit), within `cancel_after_seconds` of the signal; otherwise it is dropped at that time (or at the
session's end) and counted as not filled, and the rules may signal again. There are no re-quotes on candles. A
`market` definition (the default) replays exactly as before. The `PASSIVE_ENTRY_NOT_FILLED` warning reports the counts.

## Swing (Phase 11, M11.5)

Swing strategies are not replayed by this backtester (a `swing` family definition is refused): `POST /api/v1/swing/backtest`
runs the SWING backtest (`SwingBacktest`, `SwingBacktestService`) on **D1 bars** over the M8.4 ledger's setups detected in a
range (their plans as recorded at detection; an untriggered cup the ledger replaced by its cup-with-handle is dropped on
that day, as the ledger does).

| Rule | SWING (live-like, default) | LEDGER (the H5 ledger's, for parity) |
|---|---|---|
| Trigger | the day's range reaches the pivot, the day did not open above the buy zone; bases also need the day's volume ≥ `volumePace` × the 50-session average. A day that closed at or above the pivot without an entry consumes the setup (the live watcher no longer sees it READY) | the first close at or above the pivot |
| Entry | the pivot, or the open when the day opened above it | the same |
| Exits | from the entry session itself (the GTT is placed as soon as the entry fills): stop first, then goal | from the next session |
| Stop / goal | a low at or below the stop fills at the stop, or at the open when the session **gapped through it**; a high at or above the goal at the goal or a higher open; both in one bar: the stop (the order inside a bar is resolved against the trade) | the same |
| Time exit | after `maxHoldingDays` (30) sessions, at the next session's open | after the ledger's 120 sessions, at the close |
| Before the trigger | a base closing below its base low (a reversal trading at its stop) FAILED; 60 sessions after detection EXPIRED | the same |

The quantity risks `riskRupees` (2,500) on the gap-adjusted distance (`entry − stop + gap% × entry`, gap 3 %); delivery
costs (STT both sides, stamp, the DP charge on the sell) come off the gross. The report has the trades (gross R as the
ledger measures it, net R after costs, holding days, gap fills), the summary, a breakdown per base type and per regime
of the entry session (`trend × volatility`), walk-forward folds (`folds`, 4: consecutive groups of trades by entry date)
and the setups that produced no trade by reason (`FAILED`, `EXPIRED`, `NO_VOLUME`, `ABOVE_BUY_ZONE`, `OPEN`, `NO_DATA`).

**Parity with the H5 ledger** (`SwingParityTest`): with `rules: LEDGER` the SWING backtest reproduces the ledger's lifecycle
on the golden fixture universe (the base-detector and lifecycle series): the same setups trigger on the same sessions at the
same entries and close on the same sessions at the same exits for the same reasons with the same R; its net R is the
ledger's R less the delivery costs over the money at risk. The SWING rules differ from the ledger's only where the live
path does (range trigger, volume, the entry session's exits, the time exit), which is why the two are reported apart.
