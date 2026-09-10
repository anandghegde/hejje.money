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

## Lifecycle evidence

The backtest module supplies `StrategyEvidence`: `DRAFT → BACKTESTED` needs a `DONE` backtest for the version;
`BACKTESTED → VALIDATED` needs a `DONE` backtest whose split scheme has an out-of-sample slice with at least 30
trades and no FAIL warning.

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
