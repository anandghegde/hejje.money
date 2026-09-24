# Jev: typed fast decisions (plan Phase 9, M9.1)

Jev is TypeSafe AI's decision model. It is not a chat model: one call sends a **state** (any JSON value) and a map of
typed **questions**, and returns one typed **answer** per question with probabilities. No text, no tool calls, no
streaming. Hejje uses it for quick classifications and decisions (news, index risk events, the Jev bot, the signal
check, trade causes; M9.3–M9.6). This page covers the connection (`money.hejje.llm.JevService`), which every one of
those goes through.

Jev is **off by default** (`hejje.jev.enabled=false`, env `HEJJE_JEV_ENABLED`) and independent of `hejje.llm.*`. With
it off, failing, over budget or late, every caller behaves as if Jev did not exist (README rule 7).

## The API (checked with a live call on 2026-09-24)

`POST https://api.surplusintelligence.ai/v1/decisions`, `Authorization: Bearer <key>`, body `{ "model", "state",
"questions" }`, model `jev-1.13`. (The shapes were first read from TypeSafe's documentation of the same model, whose
endpoint was `https://api.typesafe.ai/v1/systemone`; Hejje now calls the Surplus Intelligence endpoint.) A live call
with one noul, one choice and one score question answered in about 1.4 s with exactly the answer fields below and
`usage: { input_tokens, output_tokens }`.

| Question `type` | `criteria` | Answer fields |
|---|---|---|
| `noul` (yes/no) | optional `{ "true": …, "false": … }` | `noul`: probability of yes, 0..1. **No confidence** |
| `choice` | map option → description or `null` (2..255 options) | `choice` (most likely option), `probabilities` per option (sum 1), `confidence` |
| `score` | ordered array of level descriptions (2..10) | `score` (probability-weighted level index, may fall between levels), `probabilities` per level index as a string key, `legend`, `confidence` |

`instructions` and every criterion may be a string, an object or an array. The response carries `model` and
`usage.input_tokens`. **The echoed `model` is an alias:** a request for `jev-1.13` came back as `"model": "jev-latest"`.
Hejje records an answer under the pinned `hejje.jev.model` whenever the echo is an alias or missing, so calibration is
never split or mixed by the echo; a concrete version in the echo is recorded as is. Errors: `401` bad key, `422` invalid request (not retried),
`429` rate limit and `529` overloaded (retried).

Model facts that shape how Hejje uses it (TypeSafe's models page and the jev-1.13 limitations page):

- **Pricing** is per input token only ($42 per billion, about ₹3.6 per million; output is free). Rate limits are about
  1,200 requests per minute and can change without notice.
- **Context**: 64k tokens per request; 32k for the state plus the longest question. Accuracy drops as the state fills
  with detail the question does not need, so callers send only the fields a question reads.
- **Keep numbers in code.** Jev does not count, compare dates or do arithmetic reliably, and reads questions
  literally. State builders pass computed values and named buckets (`above`, `far_below`, `heavy`), not raw series to
  compare; thresholds on answers are applied in code.
- **Aliases move.** `jev-latest` changes on a release; Hejje pins `jev-1.13` because calibration (M9.2) is measured
  per model version. Move to a new version on purpose, and re-measure.

## Calling it

```java
JevQuestionSet set = sets.get("news");                       // config/jev/news.yaml
JevResult r = jev.evaluate("news", "INFY", state, set);        // deadline hejje.jev.timeout
if (r.ok()) {
    double relevant = r.noul("relevance", 0);
    JevAnswer direction = r.answer("direction").orElseThrow();
}
```

- `evaluate(purpose, subject, state, set[, deadline])` **never throws for a Jev problem**. `ok=false` comes with an
  `outcome`: `FAILED`, `TIMEOUT`, `BUDGET`, `CIRCUIT_OPEN` or `DISABLED`, and the caller falls back. A question set
  larger than `max-questions-per-call` is a programming error and throws.
- **Deadline**: the whole call, retry included, ends within the deadline (default 2500 ms). A hung request returns
  `TIMEOUT` at the deadline.
- **Retry**: once, only for a retryable failure (timeout, I/O, 429, 5xx including 529), and only when at least half the
  deadline is left. No backoff sleep.
- **Circuit breaker**: `circuit-breaker.failure-threshold` consecutive retryable failures open it for `open-for`
  (one `JEV_CIRCUIT_OPEN` audit event when it opens); afterwards one trial call is let through.
- **Cost cap**: `daily-cost-cap` rupees per IST day from the recorded estimates; reaching it answers `BUDGET` until the
  next day, with one `JEV_BUDGET_EXCEEDED` audit event.
- **State hygiene**: `NaN` and infinities anywhere in the state are sent as `null` (the API rejects them).
- **SIM cache**: in SIM mode (`hejje.jev.sim-cache`, on), a state already answered with the same question set name and
  version is answered from the store (`outcome=CACHED`, no network, no cost), so a re-run session gets identical
  answers.

## Question sets

A set is a file `config/jev/<name>.yaml` (bundled into the jar as `classpath:jev/<name>.yaml`):

```yaml
name: news
version: "1"             # bump whenever a question, option, level or a threshold reading the answers changes
questions:
  relevance:
    type: noul
    instructions: Is the story in `title` and `summary` about the company `symbol`?
  direction:
    type: choice
    instructions: What is the tone of the story for `symbol`?
    criteria: { bullish: …, bearish: …, neutral: no clear direction }
  materiality:
    type: score
    instructions: How likely is the story to move `symbol` today?
    criteria: [Routine, Notable, Clearly price-moving]
```

Questions refer to state fields by name in backticks. Per-call questions (one per symbol, say) are added with
`set.with(Map.of(...))`; they keep the set's name and version. Answers are never compared across versions.

## Question sets in use

| Set | Version | Purpose(s) | Used by |
|---|---|---|---|
| `news` | 1 | `news` (calibrated as `news.direction`) | news classifier `jev` / `shadow` (docs/news.md, M9.3) |
| `news-risk` | 1 | `news-risk` | index risk events from headlines (docs/news.md, M9.3) |
| `bot-stage1` | 1 | `bot-stage1` (calibrated per `long_i` / `short_i`, `DIRECTION`) | the Jev bot's first stage (M9.5) |
| `bot-stage2` | 1 | `bot-stage2` (`setup`, `ENTRY_1R`), `signal-check` | the Jev bot's second stage; the signal check (M9.5) |
| `bot-position` | 1 | `bot-position` (`exit_now`, `take_profit`, `EXIT`) | the Jev bot's open positions (M9.5) |
| `trade-cause` | 1 | `trade-cause` | Jev's reading of a closed trade's cause beside the rules (docs/analytics.md, M9.6) |

A set may carry a `params` block (thresholds, weights, templates) that Hejje applies in code; it is part of the set,
so changing a threshold bumps the version like changing a question.

## What is stored (V43)

| Table | Content |
|---|---|
| `jev_call` | purpose, subject, set name and version, model, state hash, latency, input tokens, estimated cost (paise, 4 decimals), outcome, error, correlation id |
| `jev_answer` | per question: type, choice, score, noul, probabilities, confidence |
| `jev_state` | the state sent, kept `state-retention-days` (30); pruned daily at 19:30 IST (skipped in SIM) |

Answers are kept: M9.2 labels them with outcomes to measure calibration.

## Endpoints

`GET /api/v1/jev/status` and `GET /api/v1/jev/calls` (`market:read`), `POST /api/v1/jev/evaluate` (`admin`, for
trying a set out; recorded with purpose `manual`). See `docs/api.md`.

## Local development and tests

`hejje.jev.base-url: fixture` selects `FixtureJev`: neutral answers (noul 0.5, the first option at probability 1,
score 0) unless a test scripts a key (`fixture.noul("urgent", 0.8)`, `fixture.script(key, (question, state) -> answer)`),
and `failWith(...)` to simulate an outage. The `test` profile runs with it.

## The Jev bot (plan M9.5)

A bot of kind `JEV` (docs/bots.md) runs in-process: it is connected to the bot hub like a WebSocket bot, and at every
decision point it asks Jev and answers with decisions. Everything downstream is the protocol's: the backing strategy,
sizing, risk, the kill switch, SIM lockstep, session reports, the leaderboard and the promotion rule.

**Deadline.** The whole point is answered within `answerWithinMs − 300 ms` (SIM lockstep), else `hejje.jev.timeout`.
Positions and stage 1 run at once; stage 2 then runs for the kept candidates in parallel, in the time left.

**State** (built from Hejje's own data, `JevMarketState` and `JevBotState`; numbers become named buckets in code):

| Block | Fields |
|---|---|
| per stock | `vwap_position` (price − session VWAP in units of the mean true range of the last 14 M1 bars: `far_above` > 1.5, `above` > 0.5, `near`, `below` < −0.5, `far_below` < −1.5), `day_range_position` (`top` ≥ 0.8, `upper` ≥ 0.6, `middle` ≥ 0.4, `lower` ≥ 0.2, `bottom`), `return_1m/5m/15m/60m` (`up` / `down` beyond ±8 bps, else `flat`), `relative_volume` (cumulative volume against the mean of the previous five sessions at the same minute: `light` < 0.7, `normal` < 1.5, `heavy` < 3, `very_heavy`), `volume_last_5m_vs_prior_10m` (`rising` > 1.3×, `falling` < 0.7×, `steady`), `last_bars` (the last 10 M1 bars as close change and range in bps) |
| `book` (only with order-book data, M9.4) | `book_imbalance` (`bid_heavy` > 0.2, `ask_heavy` < −0.2, `balanced`), `buy_sell_quantity` (`buyers` > 1.2, `sellers` < 1/1.2, `even`), `trade_flow_5m` (up-volume share of the last 5 M1 bars: `buying` > 0.6, `selling` < 0.4, `mixed`) |
| `index` | `nifty_5m/15m/60m` returns, `breadth_above_vwap` over the bot's universe (`most` ≥ 2/3, `half` ≥ 1/3, `few`), `regime_trend`, `regime_volatility`, `pulse`, `market_condition` |
| `position` | `side`, `unrealised_bps`, `unrealised_r`, `best_bps`, `giveback_bps`, `minutes_held`, `stop_at_break_even` |

A field whose data is missing is left out. **Without order-book data** the `book` block is absent and the stage-2
questions that read it (`params.book-questions`: `flow_alignment`, `one_sided`) are removed from the call — never a
fake "balanced".

**Stages** (thresholds in the sets' `params`):

1. **Stage 1**, one call over the symbols without a position: `long_i` / `short_i` per symbol (from the templates),
   `regime`, `risk_off`. The top 3 per side at P ≥ 0.4 are kept; shorts are dropped in `trend_up`, longs in
   `trend_down`.
2. **Stage 2**, one call per candidate: `setup`, the scores `trend_quality`, `flow_alignment` (book only),
   `index_alignment`, `liquidity`, and `one_sided` (book only). Entry when the setup matches the side, P(setup) ≥ 0.55,
   its confidence ≥ 0.4, the weighted composite of the asked scores (level / (levels − 1); weights 0.35 / 0.2 / 0.25 /
   0.2, renormalised over the asked ones) ≥ 0.5, and no asked score < 0.25. The stop is `StopSuggester`'s: 1.5 × ATR(14)
   of the bot's timeframe, within the mode's `maxStopDistance`. **No target**: exits are Jev's or the stop. The decision's
   `confidence` is P(setup).
3. **Positions**, one call per open position at every point: EXIT when `thesis` < 1 or `exit_now` ≥ 0.7; TAKE_PROFIT
   at `take_profit` ≥ 0.7; MOVE_STOP to the entry when `thesis` < 1.5, the position is up ≥ 0.5R and the stop is not
   there yet; EXIT after 30 minutes without profit (time stop); else HOLD. A JEV bot confirms exits over two
   consecutive points (`exitConfirmVotes`, docs/bots.md).

**Failure.** A failed, late, over-budget or disabled Jev call answers `NONE` for entries and `HOLD` for positions
(stops and the force exit still protect); the point is answered, never SKIPPED because of Jev.

**Calibration.** Stage-1 answers are recorded as `bot-stage1` (`DIRECTION`), stage-2 setups as `bot-stage2`
(`ENTRY_1R` with the suggested stop, entered or not), position answers as `bot-position` (`EXIT`), and executed entries
as `bot:<name>` confidence (docs/calibration.md).

**SIM.** With the SIM cache, a state already answered is answered from the store, so replaying a day is free and
gives the same answers. Decisions are recorded once per (bot, point, instrument) and point ids are dated, so the same
day is replayed with another bot of the same configuration. The bot's knowledge cutoff flags SIM days before the
model's release, as for LLM bots.

## Signal check (plan M9.5)

A second opinion on every strategy signal with the `bot-stage2` questions: see docs/signals.md "Jev signal check"
(annotation, calibration purpose `signal-check`, and the `off` / `caution` / `approval` gate that calibration must
allow).
