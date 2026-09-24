# Phase 9 — Jev fast decisions, microstructure, trade causes, pace and passive entries

Goal of the phase: add **Jev** (TypeSafe AI's decision model) as a fast, typed classifier for quick decisions, and
fold in the gaps found by comparing Hejje with `arimanyus/warrenduffer` (2026-09-23): order-book and trade-flow
features, confidence calibration, a cause for every trade, overtrading and loss-streak pacing, a size cut on
index-level risk-event days, exit-vote hysteresis for bots, and passive entries that re-quote.

Jev is not a chat model. One call sends a `state` (any JSON object) and a set of typed **questions** — `noul`
(yes/no), `choice` (one of the given labels) or `score` (a rubric of ordered levels) — and returns an answer per
question with a probability (per choice / level) and a confidence. No text, no tool calls, no streaming. TypeSafe
claims the probabilities are calibrated against outcomes; this phase treats that as a hypothesis and measures it
(M9.2) before any Jev number changes a decision.

Decisions taken with the user (2026-09-23):

| Question | Decision |
|---|---|
| Jev in Hejje | Yes, for quick decisions and classifications: news, index risk events, an in-process bot, a second opinion on strategy signals, trade causes |
| Kotak Neo adapter | Not in this phase |
| Everything else from the warrenduffer gap analysis | In this phase (M9.4, M9.6–M9.8) |
| Role of Jev numbers | Evidence first. A Jev answer changes a decision only through a flag that is refused until M9.2's calibration bar passes for that purpose |

Binding rules for this phase (on top of plan/README §4):

- **Jev is optional and off by default** (`hejje.jev.enabled=false`, README rule 7). With it off, failing, over budget
  or past its deadline, every caller behaves exactly as at the end of Phase 8 (news falls back to the configured
  LLM classifier or is unassessed; the Jev bot answers `NONE`; the signal check is absent).
- **Hard deadlines.** Every Jev call has a deadline (`hejje.jev.timeout`, 2500 ms). A retry happens only when at least
  half the deadline is left; no backoff sleep past the deadline; no fallback to a chat model on a decision path.
- **Every answer is stored** with its state hash, question set version and model version (`jev_answer`), so it can be
  labelled later (M9.2) and replayed (SIM cache).
- **Question sets are versioned files** (`config/jev/*.yaml`); changing a question, a label or a threshold bumps the
  set's version, like `hejje.regime.classifier-version`. Answers are never compared across versions.
- **Deterministic core unchanged** (README rule 10). Strategy signals, backtests and risk never call Jev. The signal
  check (M9.5) annotates a signal; with its gate on, it can add a caution or require approval, never create or size a
  trade.
- **Microstructure values are live-only.** Historical candles carry no depth or tick flow; indicators that need them
  are `NOT_READY` in backtests over history, so a rule using them can only pass on recorded or live ticks.

New tables start at V43. New module: `money.hejje.calibration` (M9.2). Jev lives in the `llm` module (public API
`money.hejje.llm.JevService` and the `Jev*` types beside it; internals in `money.hejje.llm.internal`).

Order: M9.1 → M9.2 → M9.3 → M9.4 → M9.5 → M9.6 → M9.7 → M9.8. M9.4, M9.6, M9.7 and M9.8 do not need Jev and can run
in parallel with M9.1–M9.3. M9.5 needs M9.1, M9.2 and (for its full state) M9.4.

---

## M9.1 Jev provider and typed-decision API  (size: M)

**Goal.** One call, `JevService.evaluate(...)`, that asks Jev typed questions about a state within a deadline and
stores every answer.

**Tasks.**
1. **Verify the API first.** Confirm the request and response shapes against TypeSafe's current docs and record them
   in `docs/jev.md`. What warrenduffer relies on (`src/model/jev.ts`): `POST https://api.typesafe.ai/v1/systemone`
   with `Authorization: Bearer <key>` and body `{ "model": "jev-latest", "state": {…}, "questions": {…} }`; the answer
   has `answers.<key>` with `type`, `choice` / `score` / `noul` (or `probability` for booleans), `probabilities`,
   `confidence`, plus `usage` and `model` (e.g. `jev-1.13.0`). Cloudflare lists a 32k-token context. The request
   rejects `NaN`, `Infinity` and `undefined` anywhere in `state`. Pricing is not public: take it from the account.
2. Config (`hejje.jev.*`, documented in `docs/config.md`): `enabled` (false), `base-url`, `api-key-env`
   (`HEJJE_JEV_API_KEY`), `model` (`jev-1.13.0`, pinned), `timeout` (2500 ms), `max-questions-per-call` (120),
   `daily-cost-cap` (rupees, shared with nothing else), `price-per-call` or `pricing` per million tokens,
   `circuit-breaker` (the `ProviderCircuits` settings), `sim-cache` (true).
3. Types in `money.hejje.llm` (public): `JevQuestion` (sealed: `Noul(instructions, criteriaTrue, criteriaFalse)`,
   `Choice(instructions, Map<label, criterion|null>)`, `Score(instructions, List<level>)`), `JevQuestionSet(name,
   version, Map<key, JevQuestion>)` loaded from `config/jev/<name>.yaml`, `JevAnswer(key, type, choice, score, noul,
   probabilities, confidence)`, `JevResult(ok, answers, latencyMs, tokens, modelVersion, callId, failure)`.
4. `JevService.evaluate(String purpose, String subject, JsonNode state, JevQuestionSet set, Duration deadline)`:
   sanitises the state (non-finite numbers → null), splits nothing (a set above `max-questions-per-call` is a
   programming error), enforces the deadline with a hard timeout independent of the HTTP client, retries once on
   429 / 5xx / reset only when ≥ half the deadline is left, updates the circuit, checks the cost cap, and never
   throws: a failure is `ok=false` with the reason. Normalises answers as warrenduffer does (boolean `probability`
   → `noul`; per-question confidence from provider metadata when not inline).
5. Storage (V43): `jev_call(id, at, purpose, subject, set_name, set_version, model_version, state_hash, latency_ms,
   tokens, cost_paise, outcome OK|FAILED|TIMEOUT|CACHED|BUDGET, error)` and `jev_answer(call_id, key, answer,
   probability, confidence, probabilities jsonb)`. Retention: answers kept (M9.2 needs them); the state itself is kept
   for `hejje.jev.state-retention-days` (30) in `jev_state(call_id, state jsonb)` for debugging and replay.
6. **SIM cache**: in SIM, a call whose `(state_hash, set_name, set_version)` was answered before returns the stored
   answers (`outcome=CACHED`, no network, no cost), so re-running a session is reproducible and free. Off outside
   SIM.
7. `FixtureJevProvider` for tests (answers registered by set + key, or a scripted function of the state), selected by
   `hejje.jev.base-url: fixture`.
8. Endpoints: `GET /api/v1/jev/status` (`market:read`: enabled, key present, circuit, today's calls / cost vs cap,
   p50/p90 latency), `POST /api/v1/jev/evaluate` (`admin`: body `{state, set}` or inline questions, for trying a set
   out; stored with purpose `manual`), `GET /api/v1/jev/calls?purpose=&subject=&limit=`.
9. Audit: `JEV_BUDGET_EXCEEDED`, `JEV_CIRCUIT_OPEN`. A TUI line under `hejje status` (enabled, latency, cost today).

**Acceptance.** With the flag off every existing test passes unchanged. A call that exceeds its deadline returns
`ok=false, TIMEOUT` within deadline + 50 ms even when the HTTP server never answers. The same SIM state asked twice
makes one network call.

**Verification.** `./gradlew test --tests '*Jev*'`: WireMock TypeSafe (normal answer, boolean-as-probability
answer, 503 then 200, 503 with too little time left → no retry, hang past the deadline, 429 opening the circuit,
cost cap reached); fixture provider test; SIM cache test; `ApplicationModules.verify()`.

**Out of scope.** Access through the Vercel AI Gateway or Cloudflare (a second `base-url` style can follow if the
direct API is not available); the agent and NL builder (they need text and tools and stay on the chat providers).

---

## M9.2 Calibration of Jev answers and bot confidence  (size: M)

**Goal.** Know whether a probability means anything: for every purpose, the hit rate per probability bucket, with
counts, against a pre-declared outcome.

**Tasks.**
1. New module `calibration` (reads `llm.jev`, `bots`, `market`, `analytics` public APIs; writes only its own tables).
2. **Outcome labels** (`calibration_label(source, source_id, key, horizon, label, labelled_at)`, V44), each defined
   in `docs/calibration.md` before any result is looked at:
   - **Entry setups** (Jev bot stage 2, signal check, bot `ENTER_*` decisions): `1` when price reaches +1R before −1R
     within `horizon-minutes` (30) from the decision, R = |entry − stop|, entry = the next M1 open; `0` when −1R comes
     first; neither within the horizon is `NONE`, excluded from hit rates but counted. Same-bar
     touches of both count as −1R first (pessimistic). This is warrenduffer's `calibrate.ts` rule.
   - **Direction answers** (stage 1 long/short, news direction): sign of the M5 close-to-close return over
     `direction-horizon-minutes` (60; news: to the next session's close) against the answer.
   - **Exit answers** (Jev bot `exit_now`, `take_profit`): `1` when the price over the next 15 minutes moves against
     the position by more than it moves for it (the exit was right), else `0`.
   Labels are computed by a nightly job at 18:45 IST from stored M1 candles (SIM: at session end from the replayed
   candles); a label never uses data before the decision's own timestamp plus one bar.
3. **Report** `CalibrationReport(purpose, set_version | bot version, from, to, n, nLabelled, buckets[{lo, hi, n,
   hits, rate, wilsonLo, wilsonHi}], brier, ece, topVsBottom)`: 10 equal-width buckets, buckets under
   `min-bucket-count` (20) shown with their count and no rate.
4. **Pass bar** per purpose (pre-registered in `docs/calibration.md`, config `hejje.calibration.*`): ≥ 300 labelled
   answers over ≥ 15 sessions, expected calibration error ≤ 0.07, and the top populated bucket's Wilson lower bound
   above the bottom populated bucket's Wilson upper bound. `CalibrationService.passes(purpose, version)` is what the
   gates in M9.3 and M9.5 check.
5. Bot confidence (gap item): `bot_decision.confidence` of `ENTER_*` decisions is labelled like entry setups, and the
   M7.5 `SimReport` gains `confidenceCalibration` (buckets, Brier, count) per bot and session; the leaderboard shows
   the bot's pooled Brier score beside expectancy.
6. API (`market:read`): `GET /api/v1/calibration?purpose=&version=&bot=&from=&to=`, `GET /api/v1/calibration/purposes`.
   TUI: `hejje calibration [purpose]` prints the bucket table. Web: a table on the bots page and the Jev status page.

**Acceptance.** Labels for a decision do not change when later candles are added; a synthetic perfectly calibrated
source scores ECE ≈ 0 and a constant-0.9 source on 50 % outcomes fails the bar.

**Verification.** `./gradlew test --tests '*Calibration*'`: hand-built M1 fixtures for +1R-first, −1R-first,
both-in-one-bar and neither; no-look-ahead test; bucket and Wilson maths; `SimReport` round trip with the new field.

**Out of scope.** Recalibrating Jev (Platt/isotonic) — only if M9.2 shows a consistent, monotone miscalibration, as a
follow-up.

---

## M9.3 News and index risk events on Jev  (size: M)

**Goal.** Classify news stories with Jev instead of (or beside) the chat LLM, and detect index-level risk events from
headlines.

**Tasks.**
1. Question set `config/jev/news.yaml` (v1) asked per (story, candidate symbol) with state `{symbol, aliases,
   title, summary, source, publishedAt}`:
   - `relevance` noul: is the story about `symbol`?
   - `direction` choice: `bullish`, `bearish`, `neutral` ("no clear direction").
   - `materiality` score: `Routine`, `Notable`, `Clearly price-moving`.
   - `novelty` score: `Repeat of known news`, `Update`, `New information`.
   - `event_type` choice over the existing `NewsClassifier.EVENT_TYPES`.
2. Mapping into the existing `news_assessment` fields so the bias formula (docs/news.md) is unchanged: `relevance` =
   noul; `direction` = P(bullish) − P(bearish); `materiality` = expected level / 2; `novelty` = expected level / 2;
   `confidence` = the direction answer's confidence; `summary` = the title (Jev returns no text); model =
   `jev:<model version>`, prompt version = `jev-news-v1`.
3. `hejje.news.classifier: llm | jev | shadow` (default `llm`). `shadow` classifies with both, uses the LLM result,
   and stores the Jev one with `shadow=true` so the two can be compared (agreement rate on direction, mean absolute
   difference on materiality) in `GET /news/classifier-comparison?from=&to=`. Switching to `jev` is allowed at any
   time (news is context, not a trade decision); the API shows M9.2's calibration for `news.direction` beside it.
4. **Index risk events** (gap item "half size on a risk-event day", its detection half): after each poll, one Jev call
   over the newest `market-headline-count` (15) market-wide titles with `risk_event_today` noul ("RBI, Fed, budget,
   CPI or another index-level risk event today?") and `event_kind` choice (`RBI`, `FED`, `BUDGET`, `CPI`, `OTHER`).
   At P ≥ `hejje.news.risk-event-threshold` (0.6) it upserts a `market_event` with source `news-jev`, type `MACRO`,
   importance `HIGH`, all-day, scope market, keyed per date and kind so repeated polls do not duplicate it. The
   existing event-risk rules then treat it like any other macro event.
5. Cost: a story reaches Jev only after deterministic alias matching, as today; `max-items-per-poll` still applies.

**Acceptance.** With `classifier: jev` and the LLM disabled, news bias is available and computed by the unchanged
formula; with Jev failing, stories stay unassessed and bias is `NEUTRAL, available=false` as before; the same risk
event seen in three polls creates one `market_event`.

**Verification.** `./gradlew test --tests '*NewsClassifier*' --tests '*NewsRiskEvent*'` with `FixtureJevProvider`
(mapping maths, shadow mode storing both, dedupe of the risk event, fallback on failure).

**Out of scope.** Changing the bias formula or the score adjuster's weight.

---

## M9.4 Order-book and trade-flow features  (size: M)

**Goal.** Use the market depth Hejje already streams: book imbalance, buy/sell quantity ratio and signed trade flow,
per bar, recorded for replay, readable by rules, bots and Jev.

**Tasks.**
1. Extend `MarketTick` (common kernel) with nullable `bidQty5` and `askQty5` (sums of the five depth levels) and
   `totalBuyQty` / `totalSellQty` (Kite's day totals); `KiteMapper.tick` fills them from `getMarketDepth()` and
   `getTotalBuyQuantity()` / `getTotalSellQuantity()` in FULL mode, null otherwise. The Dhan adapter fills them when
   its feed carries them, else null. Constructors used by LTP/QUOTE ticks keep nulls.
2. `TickRecorder` writes four new nullable Parquet columns; `ReplayMarketDataSource` and `SessionReplay` read them
   when present and leave them null on older files (schema check, no migration of old files).
3. `BarMicro` per (instrument, M1 bar), built in `MarketPipeline` next to `CandleBuilder`: `imbalanceClose`
   ((bid5 − ask5) / (bid5 + ask5) at the last tick of the bar), `imbalanceMean` (tick-weighted), `buySellRatio`
   (totalBuy / totalSell at bar close), `upVolumeShare` (volume deltas between consecutive ticks signed by the price
   change, as warrenduffer's `flowShare`; zero-change ticks ignored), `ticks`. Aggregated to M5/M15 like candles.
   Kept in memory for the session and persisted to `bar_micro` (V45) for the operational window (15 sessions), not to
   the Parquet history.
4. Indicators (docs/indicators.md, DSL): `book_imbalance`, `book_imbalance_mean`, `buy_sell_qty_ratio`,
   `flow_up_share(n)` (volume-weighted over the last n bars). `NOT_READY` when the bar has no micro data, so a
   backtest over candle history never passes a rule that uses them; the backtester says so in its warnings when a
   definition references them.
5. The bot `decision_point` message gains `micro` per instrument (null when absent); the harness screen shows
   imbalance and flow share in the candidates panel.
6. `docs/market-data.md`: which modes carry depth, the approximation in `upVolumeShare` (polled cumulative volume,
   not trade prints), and the live-only rule.

**Acceptance.** Replaying a recorded day twice gives byte-identical `bar_micro`; a Phase 8-era Parquet file replays
with micro null and no error; an indicator with no micro data is `NOT_READY`.

**Verification.** `./gradlew test --tests '*BarMicro*' --tests '*TickRecorder*' --tests '*Indicator*'`: hand-computed
ticks → micro values; old-schema Parquet fixture; Kite FULL-mode tick mapping with depth; golden values for the new
indicators.

**Out of scope.** Order-flow strategies (a follow-up after data has been recorded for a few weeks); L2 beyond 5 levels.

---

## M9.5 Jev bot and Jev signal check  (size: L; split bot and signal check if needed)

**Goal.** Jev making quick intraday decisions inside Hejje: a built-in bot that ranks, enters and manages positions
through the bot protocol, and a second opinion on every strategy signal.

**Tasks.**
1. **Bot kind `JEV`** (bots module, runs in-process, no WebSocket): registered like other bots (`kind: JEV`,
   universe, timeframe, `questionSet` name); gets decision points from the same dispatcher and answers within
   `answerWithinMs` using `JevService` with deadline = `answerWithinMs` − 300 ms. Everything downstream (backing
   strategy, sizing, risk, SIM lockstep, reports, leaderboard, promotion gate) is unchanged.
2. **State builder** from hejje's own indicators (not warrenduffer's): last price, `vwap` distance in ATR units
   bucketed `far_above | above | near | below | far_below`, day-range position, returns over 1/5/15/60 minutes
   bucketed at ±8 bps, `relative_volume`, last 5 m vs prior 10 m volume, the last 10 M1 bars, and — when M9.4 data
   is present — imbalance, buy/sell ratio and flow share; the index block (NIFTY returns, breadth above VWAP, regime,
   pulse, market condition). Absent microstructure removes those fields and switches to the candles-only question
   variants (never a fake "balanced").
3. Question sets `config/jev/bot-stage1.yaml`, `bot-stage2.yaml`, `bot-position.yaml` (v1), ported from warrenduffer
   `src/model/questions.ts`:
   - Stage 1, one call over the universe: `long_i` / `short_i` noul per symbol, `regime` choice, `risk_off` noul.
     Keep the top 3 per side at P ≥ `stage1-min-prob` (0.4); drop shorts in `trend_up` and longs in `trend_down`.
   - Stage 2, one call per kept candidate (parallel): `setup` choice (`long_continuation`, `short_continuation`,
     `chop`, `stretched`), scores `trend_quality`, `flow_alignment` (micro only), `index_alignment`, `liquidity`,
     `one_sided` noul (micro only). Entry when the setup matches the side, P(setup) ≥ 0.55, confidence ≥ 0.4, the
     weighted composite ≥ 0.5 and no single score < 0.25 (weights and thresholds in the set file). Stop from
     `StopSuggester` (1.5 × ATR clamped by `maxStopDistance`); **no target** (exits are Jev's or the stop); decision
     `confidence` = P(setup).
   - Position, one call per open position at every point: `thesis` score (`Broken` … `Strengthening`), `exit_now`
     noul, `extended` score, `take_profit` noul, with the position block (side, entry, stop, unrealised bps and R,
     best favourable bps, giveback from peak, minutes held, stop at break-even). `EXIT` when thesis < 1 or exit_now
     ≥ 0.7; `TAKE_PROFIT` at take_profit ≥ 0.7; `MOVE_STOP` to entry when thesis < 1.5 and the position is up ≥ 0.5R;
     time stop after 30 minutes when not in profit.
   - Jev failure or timeout → `NONE` for entries and `HOLD` for positions (stops and force exit still protect).
4. **Exit-vote hysteresis for all bots** (gap item): bot registration gains `exitConfirmVotes` (default 1 = today's
   behaviour; the Jev bot uses 2). An `EXIT` / `TAKE_PROFIT` executes only after that many consecutive points voted
   it for the same position; earlier votes are recorded with outcome `NOTED` and reason `awaiting_confirmation`.
   Stops, force exits and the kill switch never wait.
5. **Signal check**: when `hejje.jev.signal-check.enabled`, every strategy signal (live, PAPER, SIM; never the
   backtester) gets one Jev call with the stage 2 set on the signal's instrument and side, off the engine thread and
   bounded by the deadline. The answer is attached to the signal's evidence (`jevCheck: {setup, p, composite,
   version}`) and shown on Today, the recommendation and the approval. It does not change the decision.
   `hejje.jev.signal-check.gate: off | caution | approval` (default off): `caution` adds `JEV_DISAGREES` to
   `cautions[]` when the setup does not match the side or P < threshold; `approval` turns an AUTO execution into an
   approval in the same case. Setting the gate above `off` is refused at startup and via config reload unless
   `CalibrationService.passes("signal-check", version)`.
6. Knowledge cutoff: Jev sees only the state, but register the bot with `knowledgeCutoff` from the model version's
   release date so SIM sessions before it are flagged as for LLM bots.
7. Docs: `docs/jev.md` (sets, thresholds, state fields, failure behaviour), `docs/bots.md` (kind `JEV`,
   `exitConfirmVotes`), `docs/signals.md` (signal check).

**Acceptance.** A SIM session with the Jev bot on the fixture provider produces the same decisions and report hash
twice; with `exitConfirmVotes=2` a single exit vote does not flatten; with Jev down the bot sends `NONE`/`HOLD` and no
point is `SKIPPED` because of Jev; the signal-check gate cannot be enabled before calibration passes.

**Verification.** `./gradlew test --tests '*JevBot*' --tests '*ExitConfirm*' --tests '*SignalCheck*'`; a SIM IT over
one recorded day with the fixture provider; on the VM: 20 SIM sessions with the real Jev bot, then the
`hejje harness leaderboard` and `hejje calibration bot-stage2` output pasted into PROGRESS.

**Out of scope.** The options leg of warrenduffer (index CE/PE from the regime); a `WILD` mode; LIVE deployment of
the Jev bot (the promotion rules decide that later).

---

## M9.6 Trade cause and entry timing in post-trade review  (size: M)

**Goal.** Every closed trade says why it ended the way it did, and losses can be grouped by cause.

**Tasks.**
1. V46: `trade_review` gains `cause` (`CLEAN_TARGET | NOISE_STOP | THESIS_BREAK | DRIFT | BAD_ENTRY | UNKNOWN`),
   `entry_timing` (`EARLY | GOOD | LATE`), `mfe_r`, `mae_r`, `cause_evidence jsonb`, and `jev_cause`, `jev_timing`
   (nullable).
2. Deterministic rules (`TradeCauseClassifier`, pure, in analytics; thresholds in `config/analytics.yaml`), evaluated
   in this order, from M1 candles of the trade plus `post-exit-minutes` (30) after it:
   - `BAD_ENTRY`: at entry, the move in the trade's direction over the previous 15 minutes was ≥ `extended-atr` (1.5)
     × ATR(14) of M1, or the entry was ≥ 2 ATR from VWAP in the trade's direction.
   - `CLEAN_TARGET`: exited at the target with MAE better than −0.5R.
   - `NOISE_STOP`: stopped out, and within `post-exit-minutes` price regained the entry and reached +1R.
   - `THESIS_BREAK`: exited by an exit rule, a bot or Jev exit, or stopped out without a recovery.
   - `DRIFT`: time or force exit with |R| < 0.3.
   - Entry timing: `EARLY` when MAE ≤ −0.7R before MFE ≥ +0.5R, `LATE` when MFE < 0.3R and the entry was in the top
     (long) / bottom (short) 20 % of the previous 30-minute range, else `GOOD`.
   The review is written when the trade closes and completed (the post-exit window) by a job 35 minutes later; the
   evidence lists the numbers each rule used.
3. When Jev is enabled, one call with `config/jev/trade-cause.yaml` (warrenduffer's attribution questions: `cause`
   choice, `entry_timing` score) fills `jev_cause` / `jev_timing`; `GET /reviews/cause-agreement?from=&to=` shows the
   confusion matrix against the rules. The rules stay the source of truth.
4. Loss attribution (docs/analytics.md) gains a `causes` breakdown; `hejje reviews` and the web review page show the
   cause and timing.

**Acceptance.** Each cause has a fixture trade that yields it; re-running the classifier on the same candles gives
the same answer; a trade closed less than 30 minutes before the session end is completed from the candles that exist
and marked `partialWindow=true`.

**Verification.** `./gradlew test --tests '*TradeCause*' --tests '*LossAttribution*'`.

**Out of scope.** Changing strategies from causes automatically.

---

## M9.7 Pace report, loss-streak allowance, risk-event size cut  (size: M)

**Goal.** See whether more trades per day hurt, and pace entries after a bad start instead of stopping outright.

**Tasks.**
1. **Pace report** `GET /api/v1/analytics/pace?from=&to=&mode=&strategy=` (and `hejje analytics pace`): expectancy
   per trade (R and rupees net of costs), count and win rate bucketed by trades taken that day (1–4, 5–8, 9–16, 17+),
   by the trade's sequence number within the day (1st, 2nd, …, 6th+) and by entry hour. Counts shown beside every rate.
2. **Loss-streak allowance** (opt-in; `RiskLimits` per mode): `lossStreakMode: BLOCK | ALLOWANCE` (default `BLOCK` =
   today's `consecutiveLosses` check). In `ALLOWANCE` mode, when today's consecutive losses reach
   `maxConsecutiveLosses` (3) or net P&L today falls to −`allowanceDrawdownRupees` (500), the day gets
   `lossStreakAllowance` (4) further entries counted from that moment; after that, new entries are rejected with
   `LOSS_STREAK_ALLOWANCE`. A winning trade does not reset the allowance. `tradesPerDayWhenGreen: LIMIT | UNLIMITED`
   (default `LIMIT`): with `UNLIMITED`, `tradesPerDay` is not enforced while net P&L today ≥ 0 (the other limits
   still apply). Shown on the risk dashboard (`allowanceUsed/allowance`, `reason`).
3. **Risk-event size cut**: `hejje.risk.macro-event-size-factor` (1.0 = off; warrenduffer uses 0.5). When the day has
   a HIGH-importance, market-scope macro event (calendar, or M9.3's `news-jev` source), the risk money used by
   `PositionSizer` for new entries of signals and bots is multiplied by the factor for the whole session. The sizing
   evidence records the factor and the event. The backtester applies it too when the event exists in `market_event`
   for the replayed date, so backtest and live size alike.
4. `docs/risk.md` and `docs/analytics.md` updated; audit `RISK_LIMITS_CHANGED` covers the new fields.

**Acceptance.** Allowance mode: after three losses, exactly four more entries pass and the fifth is rejected with the
reason; BLOCK mode is unchanged; the size factor halves quantity on an event day and leaves other days alone.

**Verification.** `./gradlew test --tests '*RiskControls*' --tests '*PositionSizer*' --tests '*PaceReport*'`.

**Out of scope.** Friction budgets per day (parsed but unused in warrenduffer too).

---

## M9.8 Passive entries with re-quote  (size: L)

**Goal.** Optionally enter at the touch instead of at market, re-pricing a few times as the quote moves, then give up.

**Tasks.**
1. DSL: `entry_order: { type: market | limit_touch, max_requotes: 3, cancel_after_seconds: 90 }` (default `market`,
   today's behaviour); strategy schema and `docs/strategy-dsl.md` updated; bots may send `entryOrder` with an
   `ENTER_*` decision.
2. Runner: `limit_touch` places a LIMIT at the best bid (long) or ask (short) from `QuoteCache`, rounded to tick. When
   the touch moves away, it modifies the order to the new touch (through the rate limiter and the executor lease), at
   most `max_requotes` times; at the limit or after `cancel_after_seconds` it cancels and the signal ends `EXPIRED`
   with reason `ENTRY_NOT_FILLED`. A partial fill keeps the filled quantity (stop placed for it) and cancels the rest.
   Risk is evaluated once on the intent; a modify never increases quantity or crosses the original signal price by
   more than `max_chase_bps` (10).
3. Fills in SIM and PAPER through `BrokerSimulation`: a resting limit fills when a later tick trades at or through its
   price (the pessimistic rule of warrenduffer's `fillAgainstBar`); backtester fill model for `limit_touch`: fills at
   the limit only if the next bar trades through it (strictly beyond), else the order lives until the cancel time.
4. Reports: entry slippage for passive entries vs market, fill rate, time to fill, count of `ENTRY_NOT_FILLED`
   (analytics slippage report and the bake-off tooling).
5. Audit `ENTRY_REQUOTED`, `ENTRY_NOT_FILLED`; docs in `docs/signals.md` and `docs/backtesting.md`.

**Acceptance.** Default `market` definitions produce byte-identical backtests to before; a `limit_touch` entry
re-quotes at most N times and cancels at the deadline; a partial fill is protected by a stop for the filled quantity.

**Verification.** `./gradlew test --tests '*PassiveEntry*' --tests '*Backtest*'` (tick-scripted touch moves, partial
fill, deadline, chase cap); replay parity test for a `limit_touch` strategy.

**Out of scope.** Iceberg or time-sliced passive orders (split orders already cover slicing).

---

## Phase 9 exit checklist

- [ ] Jev answers within its deadline at p90 on the VM; with `hejje.jev.enabled=false` the system behaves as at the end of Phase 8.
- [ ] Calibration reports exist for every Jev purpose and for bot confidence, each with counts; the pass bar is in `docs/calibration.md` before any result.
- [ ] News runs in `shadow` for at least 10 sessions; the comparison report is in PROGRESS with the decision to switch or not.
- [ ] Depth and flow are recorded and replayed; microstructure indicators are `NOT_READY` on candle history.
- [ ] The Jev bot has ≥ 20 SIM session reports; leaderboard and calibration output in PROGRESS.
- [ ] Every closed trade has a cause and timing; loss attribution shows causes.
- [ ] Pace report, allowance mode and the event size factor are documented and off by default.
- [ ] `market` entries unchanged; `limit_touch` has parity and slippage numbers from SIM or PAPER.
