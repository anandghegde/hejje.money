# Phase 2 — Strategy Platform

Goal of the phase (PRD §68): strategies as first-class versioned objects, a realistic backtester, six initial strategies with evidence, Hejje Score v1, a live signal engine that shares code with the backtester, the Today screen, Lab v1, and attribution.

Order: M2.1 → M2.2 → M2.3 → M2.4 → M2.5 → M2.6 → M2.7 → M2.8 → (M2.9 optional). M2.2 can start in parallel with M2.1.

---

## M2.1 Strategy definition format, versioning, storage  (size: L)

**Goal.** A deterministic, language-independent strategy DSL (PRD §10) with immutable versions.

**Tasks.**
1. JSON Schema at `docs/strategy-schema.json` (authored as YAML in `strategies/`), top-level fields: `name, family {TREND, MEAN_REVERSION, INDEX, OPTIONS}, description, universe` (list of `symbol` or selectors `nearest_future: NIFTY`, `index: NIFTY 50`), `timeframe, direction {long, short, both}, entry {all|any: [conditions]}, exit {all|any: [conditions]} (optional), stop {type: opening_range_low|opening_range_high|atr_multiple|percent|points|swing_low|swing_high|prev_day_low|prev_day_high, value?}, target {type: risk_multiple|points|percent|vwap|none, value?}, trailing_stop {type: atr_multiple|percent|breakeven_at_r, value}?, trade_window {start, end}, force_exit_time (default 15:10), max_trades_per_day, max_holding_minutes?, position_sizing {type: risk_based, risk_rupees | risk_percent_of_capital}, product (MIS default), regime_preferences {trending|ranging|volatile|…: preferred|neutral|avoid}, event_rules {high_risk_event_within_minutes, action: block|caution|allow}, risk_overrides {min_reward_risk, max_quantity}?`.
2. Condition grammar (document in `docs/strategy-dsl.md`): `<expr> <op> <expr>` with `op ∈ {>, <, >=, <=, ==, crosses_above, crosses_below}`; expressions are literals, series refs with optional bar offset (`close`, `close[1]`, `high`, `low`, `open`, `volume`), indicator calls (`ema(20)`, `sma(50)`, `rsi(14)`, `atr(14)`, `vwap`, `bb_upper(20,2)`, `bb_lower(20,2)`, `opening_range_high(15m)`, `opening_range_low(15m)`, `relative_volume(20)`, `prev_day_high`, `prev_day_low`, `prev_day_close`, `gap_pct`, `session_minutes`, `adx(14)`), simple arithmetic `+ - * /`. Hand-written recursive-descent parser → AST; evaluator interface `ConditionEvaluator.evaluate(ast, BarContext) → EvalResult{passed, observedLhs, observedRhs}` (observed values feed evidence lists).
3. Validation: schema → semantic (known indicators and arities, timeframe compatible with opening-range spec, stop present unless `direction` is exit-only, trade window inside session, `force_exit_time` before 15:20 for MIS). Errors are structured `{path, message}`.
4. Tables: `strategy(id, slug, family, name, created_at, retired_at)`; `strategy_version(id, strategy_id, version int, definition_yaml text, definition_hash, change_note, parent_version_id, created_by, created_at, status {DRAFT, BACKTESTED, VALIDATED, PAPER, LIVE, PAUSED, RETIRED})` unique `(strategy_id, version)`; `strategy_deployment(id, version_id, mode, instrument_ids jsonb, autonomy_level int (0–3 in this phase), enabled, params jsonb, created_at, paused_at, pause_reason)`.
   - Status transitions enforced in `StrategyLifecycle`: `DRAFT → BACKTESTED` (a backtest exists) `→ VALIDATED` (an OOS/walk-forward backtest exists and passes min-trade rule) `→ PAPER → LIVE`; `PAUSED` from PAPER/LIVE; `RETIRED` from anywhere. LIVE requires VALIDATED and, from Phase 5, paper history. Every transition audited.
5. Bundled definitions in `strategies/*.yaml` loaded at startup: insert if hash unknown as a new version with `change_note: "bundled"`.
6. Endpoints (`strategies:read|write`): `GET /strategies`, `POST /strategies` (yaml body), `GET /strategies/{id}`, `GET /strategies/{id}/versions`, `POST /strategies/{id}/versions` (new version from yaml + change note), `GET /strategies/{id}/versions/{v}`, `POST /strategies/validate`, `POST /strategies/{id}/clone`, `POST /strategies/{id}/versions/{v}/status` (lifecycle), `POST /strategies/{id}/versions/{v}/deployments`, `PUT /deployments/{id}` (enable/pause), `GET /deployments`.

**Acceptance.** Parser golden tests (≥30 expressions incl. precedence and errors); PRD §10 example parses and validates; a version row can never be updated (test); lifecycle rejects `DRAFT → LIVE`.

**Verification.** `./gradlew test --tests 'money.hejje.strategy.*'`

---

## M2.2 Indicator library  (size: M)

**Goal.** Deterministic, incremental indicators shared by backtester and live engine, with no look-ahead by construction.

**Tasks.**
1. `market.indicators` package: `IndicatorContext` fed one closed candle at a time per (instrument, timeframe); indicators are objects with `update(candle)` and `value()`/`value(offset)`; values are only readable after the bar closes. Implement: SMA, EMA, RSI (Wilder), ATR (Wilder), Bollinger, session-anchored VWAP (reset at 09:15), opening range high/low for N minutes (available only after 09:15+N), relative volume (bar volume ÷ mean volume of the same time-of-day slot over the last N sessions), previous-day high/low/close (from D1 candles), gap %, ADX, highest/lowest over N, session minutes.
2. Warm-up: `IndicatorContext.warmUp(List<Candle>)` from historical store; each indicator reports `isReady()`; conditions referencing non-ready indicators evaluate to `NOT_READY` (never true).
3. Golden fixtures: generate expected values once with an independent implementation (a one-off Python script in `research/tools/gen_indicator_fixtures.py` using `pandas-ta` or `TA-Lib`), commit the CSV, assert within 1e-6.

**Acceptance.** Every indicator has a golden test; opening range before 09:30 (15m) is `NOT_READY`; VWAP resets per session.

**Verification.** `./gradlew test --tests 'money.hejje.market.indicators.*'`

---

## M2.3 Backtest engine, cost model integration, splits, quality warnings  (size: L)

**Goal.** Realistic, deterministic bar-replay backtester producing all PRD §12.1 metrics with visible costs and quality warnings.

**Tasks.**
1. `backtest` module: `BacktestSpec{versionId, instruments, timeframe, from, to, fillModel{NEXT_OPEN|BAR_CLOSE, slippageBps}, costModelVersion, splits{inSample, validation, outOfSample | walkForward{trainMonths, testMonths, anchored}}, initialCapital}`.
2. Replay loop per instrument per session: feed candles to `IndicatorContext`; on bar close evaluate entry (respect trade window, max trades/day, one open trade per instrument); entry fill at next bar open (default) ± slippage; stop/target/trailing evaluated on each subsequent bar using high/low with the conservative rule (if both touched in one bar, assume stop hit); rule-based exits at bar close; force exit at `force_exit_time`; costs applied per fill via `CostModel`. Position size from `position_sizing` and stop distance.
3. Output: `BacktestTrade{entryTime, exitTime, side, qty, entryPrice, exitPrice, stop, target, grossPnl, costs, netPnl, rMultiple, exitReason, evidence}`; metrics per split and overall exactly PRD §12.1 plus equity curve, drawdown series, R-multiple histogram, monthly/day-of-week/hour tables; `gross vs cost vs net` summary (PRD §12.2).
4. Quality warnings (PRD §12.3): trades < 100 (warn) / < 30 (fail-to-validate); top-5-trade concentration > 50% of net profit; missing sessions > 2% of expected; parameter count vs trades ratio; fills at bar extremes; IS/OOS expectancy gap > 50%; zero-volume bars used.
5. Persistence: `backtest(id, version_id, spec jsonb, status {QUEUED, RUNNING, DONE, FAILED, CANCELLED}, started_at, finished_at, metrics jsonb, warnings jsonb, result_hash, engine {JAVA, PYTHON})`, `backtest_trade(...)`. Equity curve as jsonb for MVP.
6. Bounded async runner (`hejje.backtest.workers`, default 2), progress %, cancel. Endpoints: `POST /backtests`, `GET /backtests/{id}`, `GET /backtests/{id}/trades?split=`, `GET /backtests?versionId=`, `DELETE /backtests/{id}` (cancel).
7. Determinism: `result_hash` over trades; running the same spec twice yields the same hash (test).

**Acceptance.**
- Synthetic dataset tests: a constructed breakout day produces exactly one trade with the expected entry/stop/exit; both-touched bar resolves to stop; force exit at 15:10.
- Metric unit tests against hand-computed values (win rate, PF, expectancy, max DD and duration, Sharpe/Sortino on a fixed trade list).
- Costs reduce net P&L by the fixture amounts; warnings fire on a 20-trade backtest.

**Verification.** `./gradlew test --tests 'money.hejje.backtest.*'`

---

## M2.4 Historical dataset and initial strategy library  (size: L, mostly data work)

**Goal.** Three or more years of intraday data and six documented strategies with baseline backtests.

**Tasks.**
1. Backfill via M1.3 for: `INDEX:NIFTY 50`, `INDEX:NIFTY BANK`, `INDEX:INDIA VIX` (M1, M5, D1); NIFTY and BANKNIFTY futures as **continuous series** `NFO:NIFTY:FUT:CONT` built by stitching the nearest contract and rolling on expiry day (document in `docs/data.md`; store per-contract raw plus the stitched series; no back-adjustment for intraday strategies since trades never span a roll); NIFTY 50 constituents from `config/universe/nifty50.yaml` (M5, D1). Data integrity report: sessions vs holiday calendar, bar counts per session, gaps.
2. Write the six strategies in `strategies/` with conventional parameters (do not tune): `nifty_orb` (15m ORB long), `nifty_orb_breakdown` (short), `vwap_trend_continuation`, `vwap_reversion`, `pdh_pdl_breakout`, `ema_pullback`. Each with a `docs/strategies/<slug>.md`: rationale, rules in words, parameters, expected regime fit, known weaknesses.
3. Run IS/validation/OOS and walk-forward backtests for each; record baseline metrics and warnings in the docs. Strategies that fail the min-trade rule stay `BACKTESTED`, others move to `VALIDATED`.
4. `research/` scaffold: `pyproject` with Polars/DuckDB, a notebook-free script `inspect_backtest.py <id>` reading the Parquet/DB for ad-hoc analysis. Optional.

**Acceptance.** Coverage report shows ≥ 3 years for all series; each strategy has documented baseline results; no strategy parameters were changed after seeing OOS results (state this in PROGRESS.md).

**Verification.** `curl /market/history/coverage`, `GET /backtests?versionId=` for each; docs present.

---

## M2.5 Hejje Score v1 and comparison  (size: M)

**Goal.** Explainable 0–100 score (PRD §14) with pluggable adjusters, and comparison APIs (PRD §11, §21).

**Tasks.**
1. `scoring` module. Documented formula in `docs/hejje-score.md`. Base score from the latest validated backtest, OOS-weighted (OOS 60%, validation 25%, IS 15%): components each mapped to 0–100 via documented piecewise functions then weighted — expectancy (R) 25%, profit factor 20%, max drawdown (R) 15%, consistency (share of profitable months) 15%, sample size 10%, walk-forward stability (std of window expectancies) 10%, slippage sensitivity (re-run with 2× slippage; drop in expectancy) 5%. Clipped 0–100.
2. `ScoreAdjuster` interface: `adjust(versionId, instrumentId, ctx) → Adjustment{name, delta, evidence[]}` bounded per adjuster. Phase 2 adjusters: `TechnicalCompatibility` (signal currently active/forming: 0..+8; conditions failing: −10..0), `RecentPaperLivePerformance` (trailing 20 trades vs backtest expectancy; 0 when no data). Phase 3 adds regime/news/event adjusters.
3. `strategy_score(id, version_id, instrument_id, computed_at, base, adjustments jsonb, final)`; recomputed on new backtests and every 5 minutes during session for deployed strategies; `GET /strategies/{id}/score` returns the breakdown table exactly like PRD §14.
4. Comparison: `GET /strategies/compare?versionIds=` (PRD §21 table incl. similar-regime placeholder), `GET /strategies/{id}/versions/compare?a=&b=` (PRD §11 table + templated verdict: e.g. "v3 improved profit factor by X but reduced trades by Y%").

**Acceptance.** Score decomposition sums to final; a strategy with < 30 OOS trades cannot exceed 50; adjusters bounded; comparison endpoints render the PRD examples from fixture backtests.

**Verification.** `./gradlew test --tests 'money.hejje.scoring.*'`

---

## M2.6 Live signal engine and exit management  (size: L)

**Goal.** The same rules evaluated live to produce signals with validity windows, and deterministic trade management for open positions.

**Tasks.**
1. `signals` module: `StrategyRunner` per enabled deployment × instrument; subscribes to `CandleClosedEvent` for its timeframe on the `TickBus`; warms up indicators from history at start; evaluates entry conditions after bar close within trade window and max trades/day; produces `Signal{id, versionId, instrumentId, direction, referencePrice, stop, target, riskPerUnit, validUntil (next bar close or strategy `signal_validity_minutes`), evidence[] (each condition with observed values), status {ACTIVE, EXPIRED, PREPARED, EXECUTED, SKIPPED, BLOCKED}}`; table `signal`; durable `SignalGeneratedEvent`; audit `SIGNAL_CREATED`.
2. Signal → intent: `POST /signals/{id}/prepare` (`orders:prepare`) → sizes with `PositionSizer` using deployment `risk_rupees` → returns a **proposed** `OrderIntent` plus a dry-run `RiskDecision` (not submitted). `POST /signals/{id}/execute` (`orders:execute`, Idempotency-Key; CONFIRM mode is the user's confirmation) → submits the intent linked to the signal, marks signal `EXECUTED`. `POST /signals/{id}/skip` with reason. `GET /signals?status=&from=`.
3. Exit management for positions with `strategy_id`:
   - On entry fill, place a broker-side protective stop (`SL-M`, role `STOP`) so protection survives a server crash; keep a software monitor as backup that fires a MARKET exit if the stop order is missing/rejected.
   - Target: rule-based or price-based; implemented in software (modify to exit on touch) — document the choice.
   - Trailing stop → modify the stop order; rule-based exits at bar close → `STRATEGY_EXIT` intent and cancel stop; force exit at `force_exit_time`; on exit fill cancel remaining child orders.
   - All of this runs without any LLM and continues if context services are down.
4. Deployment controls: enable/pause/stop; pausing stops new signals but keeps managing open positions.
5. Parity test harness: feed a recorded session's candles to the live engine (replay) and to the backtester with `fillModel=BAR_CLOSE`; assert identical signal times and levels.
6. Metrics `signal.to.ack`, `signal.to.fill`; audit `STRATEGY_RECOMMENDED`, `USER_APPROVED`, `STOP_MODIFIED`.

**Acceptance.** Parity test passes for all six strategies on two recorded sessions; signal expires after its window; max trades/day respected; on entry fill a stop order appears; force exit fires at 15:10 in replay; server restart with an open strategy position restores the runner and re-attaches the stop.

**Verification.** `./gradlew test --tests 'money.hejje.signals.*'`

---

## M2.7 Today screen, Strategies, Lab v1, attribution, post-trade review  (size: L)

**Goal.** The decision surface (PRD §8, §73) and the research surface (PRD §23) plus P&L attribution (PRD §53–55).

**Tasks.**
1. Recommendation engine (`scoring` or new `recommend` package): for each deployed strategy × instrument compute `Recommendation` (PRD §29 shape; `news_bias=null`, `event_risk="UNKNOWN"` until Phase 3): `score × signal validity × risk eligibility (dry-run risk)` → decision `TRADE | WAIT | AVOID` (`TRADE WITH CAUTION` arrives in Phase 3), `hard_blocks[]`, supporting evidence and risks (PRD §20 structure). Threshold `hejje.recommend.min-score` (default 70). "No Trade" when nothing qualifies. `GET /today` → `{header{indexQuotes, vix, regime:null, breadth:null, eventRisk:null}, best, ranked[], noTrade}`; table `recommendation` for history.
2. Analytics module: `GET /analytics/pnl?groupBy=strategy|version|instrument|weekday|hour|regime(null now)&from=&to=&mode=`; manual trades attributed `strategy=MANUAL`.
3. Post-trade review: on position close (`PositionChangedEvent` to zero) create `trade_review(id, position_id, strategy_version_id, signal_id, outcome_r, expected_setup_valid, entry_slippage_bps, exit_slippage_bps, rule_adherence_pct, context jsonb (placeholders), notes)`; for manual trades compare against the selected strategy if one was chosen. `GET /reviews`, `GET /reviews/{id}`.
4. Web: `/today` (header, Best Hejje card with score breakdown and EXECUTE → prepare modal showing proposed order + risk checks → CONFIRM, DETAILS, SKIP; ranked table; No-Trade state), `/strategies` (score, status, deployments, key metrics), `/strategies/{id}` (definition rendered as rule list, versions with compare, backtest metrics tables and equity chart, trade list, deploy/pause, score breakdown), `/lab` (YAML editor with live validation, clone, new version with change note, run backtest with split options, backtest result viewer, version compare), `/trades` (attribution column, review link), `/reviews/{id}`, P&L breakdown widgets on `/risk` or a new `/analytics` route.
5. Playwright: paper flow signal → Today → execute → filled → close → review exists (use replay data source in dev).

**Acceptance.** E2E paper flow passes; Today shows "No strategy currently meets your minimum quality threshold" when none qualifies; attribution totals match trades.

**Verification.** `./gradlew test --tests 'money.hejje.analytics.*' --tests '*Recommendation*'` and `cd web && npm run e2e`

---

## M2.8 TUI: best, strategies, execute flow  (size: S)

**Tasks.** `hejje best` (Best Hejje card + ranked table), `hejje strategies`, `hejje strategy <id>` (score breakdown, metrics, deployments), `hejje signals`, `hejje execute <signal-id>` (shows prepared order + risk checks, asks `y/N`), `hejje skip <signal-id>`. Dashboard: Best Hejje card with `[E] Execute [D] Details [S] Skip` keys.

**Verification.** `cd tui && go test ./... && go build ./...`

---

## M2.9 (optional) Python research worker  (size: M)

**Goal.** Faster parameter sweeps without making Python the source of truth.

**Tasks.** `research/hejje_research`: Polars/DuckDB evaluator for the same DSL (parser port + indicators), invoked as `python -m hejje_research backtest --spec spec.json`; results imported through `POST /backtests/import` with `engine=PYTHON`; **parity suite**: same strategies and dataset as the Java golden tests must produce identical trades before any Python result is allowed to change a strategy's status.

**Out of scope until Phase 4:** experiment orchestration (M4.7 uses this worker if present).

---

## Phase 2 exit checklist

- [ ] Six strategies validated (or explicitly flagged), docs with baseline metrics.
- [ ] Parity backtester ↔ live for all strategies.
- [ ] PAPER: signal → Today → confirm → fill → stop order placed → close → review; audit chain from `SIGNAL_CREATED` to `POSITION_CLOSED` links signal, intent, order, broker id.
- [ ] Score breakdown documented and reproducible.
- [ ] Trading core runs with `hejje.llm.enabled=false` (always true in this phase).
