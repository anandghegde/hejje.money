# Phase 3 — Context

Goal of the phase (PRD §69): regime classification, Pulse, event calendar and event risk, news bias, and a context engine that turns them into TRADE / TRADE WITH CAUTION / WAIT / AVOID with structured evidence. Every service here is optional at runtime: if it is down, adjusters return zero with an "unavailable" evidence item and the execution path is unaffected.

Order: M3.1 → M3.2 → M3.3 → M3.4 → M3.5. M3.3 and M3.4 are independent of each other.

---

## M3.1 Market regime engine  (size: L)

**Goal.** Deterministic classification of today and every historical session (PRD §13), and regime-conditional strategy statistics.

**Tasks.**
1. `regime` module. `RegimeSnapshot{date, asOf, trend, volatility, opening, breadth, intradayStructure, eventEnvironment, features jsonb, classifierVersion}` with enums exactly as PRD §13. Rules (document in `docs/regime.md`, all thresholds in `config/regime.yaml`):
   - Trend: NIFTY 50 D1 close vs EMA20/EMA50, EMA slope over 5 sessions, ADX(14) → `STRONG_UP | UP | RANGE | DOWN | STRONG_DOWN`.
   - Volatility: INDIA VIX percentile over trailing 250 sessions and ATR(14)/close percentile → five buckets.
   - Opening: gap % vs previous close with thresholds; after 09:30 refine to `GAP_CONTINUATION | GAP_REJECTION` using first-15-minute close vs open.
   - Breadth: advance/decline and % above VWAP across `nifty50.yaml` constituents (needs quotes; falls back to `UNKNOWN`).
   - Intraday structure: progressive — range expansion vs opening range, count of VWAP crosses, close-to-range position; final label at session close.
   - Event environment: from `events` module (M3.3); `NORMAL` until then.
2. Historical labeling job: label every session in the historical store (`market_regime` table keyed by date + classifier version); re-run when the classifier version changes. Intraday partial snapshots stored every 5 minutes during session (`market_regime_intraday`).
3. Regime-conditional backtest stats: extend backtest metrics with per-regime groupings (trend × volatility by default; configurable dims) and a `similarRegime` block computed against the current snapshot: trades, win rate, expectancy, PF. Exposed in `GET /backtests/{id}` and on the strategy detail.
4. `RegimeCompatibilityAdjuster` (bounded −10..+10): strategy `regime_preferences` for the current labels (preferred +3, avoid −6) plus similar-regime expectancy relative to overall expectancy (scaled, ±7), with evidence listing both.
5. Endpoints (`market:read`): `GET /context/regime` (current with features), `GET /context/regime/history?from=&to=`.

**Acceptance.** Rule unit tests per dimension with fixture candles; labeling is reproducible (same hash across two runs); adjuster evidence explains each point; regime `UNKNOWN` when inputs missing rather than a crash.

**Verification.** `./gradlew test --tests 'money.hejje.regime.*'`

---

## M3.2 Technical Pulse and Market Pulse  (size: M)

**Goal.** "What kind of market are we trading today?" (PRD §16) as a rule-based composite with evidence.

**Tasks.**
1. Inputs (all from existing data): index trend and VWAP relationship, breadth and A/D, relative volume, VIX level and change, sector index relative strength (`INDEX:NIFTY BANK`, `NIFTY IT`, `NIFTY AUTO`, `NIFTY PHARMA`, `NIFTY FMCG`, `NIFTY METAL`, `NIFTY REALTY`, `NIFTY MIDCAP 100` — add to watchlist and `config/universe/sectors.yaml`), futures basis (nearest future − index), gap behavior, momentum (ROC).
2. `TechnicalPulse{direction BULLISH|NEUTRAL|BEARISH, strength STRONG|MODERATE|WEAK, score −100..100, evidence[]}` computed every minute during session by weighted rules in `config/pulse.yaml`; `MarketPulse` table per PRD §16.2 (regime, volatility, breadth, sector labels, global context = `NEUTRAL` placeholder until a data source exists).
3. `GET /context/pulse`; web `/pulse` screen (composite, sector strength bars, breadth, VIX sparkline, evidence list); TUI `hejje pulse`; Today header now shows Regime and Breadth.

**Acceptance.** Fixture-based tests produce expected labels; missing sector quotes degrade to `UNKNOWN` for that row only.

**Verification.** `./gradlew test --tests '*Pulse*'`

---

## M3.3 Event calendar and event risk  (size: L)

**Goal.** Structured events separate from news (PRD §18), proximity-based risk, and strategy-specific event rules.

**Tasks.**
1. `events` module: `market_event(id, type, scope {MARKET, INSTRUMENT}, instrument_id, title, starts_at, ends_at, all_day, source, confidence, raw jsonb, imported_at)`; type enum covering PRD §18.1–18.2.
2. Sources (pluggable `EventSource` interface, each behind a config flag):
   - `ComputedEventSource`: NSE holidays, weekly/monthly F&O expiries (from instrument expiries), index rebalance dates (config).
   - `CuratedYamlSource`: `config/events/macro-2026.yaml` for RBI MPC, FOMC, India/US CPI, US jobs, Budget, elections; documented quarterly maintenance procedure.
   - `CsvImportSource`: `POST /events/import` (CSV: corporate actions, board meetings with purpose, results dates) — the manual path that always works.
   - `NseCorporateActionsFetcher` (optional, flag): best-effort fetch of NSE corporate-action/board-meeting feeds; must fail silently into the import path if blocked.
3. `EventRiskEvaluator.evaluate(instrumentId, now) → EventRisk{level LOW|MEDIUM|HIGH, nextEvent, minutesTo, evidence[]}` with defaults in `config/events.yaml` (results today → HIGH; macro within 60 min → HIGH; macro today → MEDIUM; expiry day for index derivatives → MEDIUM; ex-date today → MEDIUM).
4. Strategy-specific rules: enforce `event_rules` from the definition in the recommendation (block → `AVOID`, caution → `TRADE WITH CAUTION`) and as a risk check `EventRuleCheck` in the pipeline for `STRATEGY_SIGNAL` intents (so a manual execute of a blocked signal is rejected deterministically). `EventRiskAdjuster` bounded −8..0.
5. Regime `eventEnvironment` now derived from today's events. Endpoints: `GET /events?from=&to=&instrumentId=`, `GET /events/risk?instrumentId=`, `POST /events` (manual add, `strategies:write`).
6. Web: calendar widget on Today and strategy detail ("Next Event — Q2 Results Today 16:00"); TUI shows next event in the best card.

**Acceptance.** Proximity tests around each threshold; a strategy with `action: block` and a results event today yields `AVOID` and a risk rejection; sources down → no events, risk `LOW` with evidence "event service unavailable".

**Verification.** `./gradlew test --tests 'money.hejje.events.*'`

---

## M3.4 News ingestion and news bias (first LLM use)  (size: L)

**Goal.** News as bounded context (PRD §17): ingest, dedupe, classify with an LLM behind an abstraction, aggregate deterministically into a bias with retained evidence.

**Tasks.**
1. `llm` module (minimal now, completed in M4.1): `LlmProvider{complete(LlmRequest), stream(...)}`, `OpenAiCompatibleProvider` (base URL, API key env, model), profiles config exactly as PRD §66C (`fast, reasoning, news, research`), `hejje.llm.enabled=false` default, timeouts, retries with backoff, `llm_call` log table (profile, model, purpose, prompt_version, input/output tokens, cost estimate, latency, correlation id, status). `StructuredOutput.ask(profile, prompt, jsonSchema)` validates the response against a schema and retries once. `FixtureLlmProvider` for tests (canned responses by prompt hash).
2. `news` module: `news_source(id, name, url, kind {RSS, ATOM, JSON}, reliability 0..1, enabled)` seeded from `config/news-sources.yaml`; poller every `hejje.news.poll-minutes` (5); `news_item(id, source_id, url, title, summary, body?, published_at, fetched_at, hash)`; dedupe by URL, hash, and normalized-title similarity within 24 h.
3. Instrument/sector matching: deterministic first — `config/aliases.yaml` (symbol → company names, tickers, sector) → candidate instruments; then LLM classification (profile `news`, prompt versioned in `resources/prompts/news_classify_v1.txt`) → `news_assessment(item_id, instrument_id|sector, relevance, direction, materiality, novelty, confidence, event_type, model, prompt_version)`. When LLM disabled: items stored, no assessment.
4. Bias aggregation (deterministic): per instrument over trailing 24 h: Σ direction × materiality × confidence × source reliability × recency decay (half-life 4 h) × confirmation factor (distinct sources); clip to [−1, 1]; label per PRD §17.1; add price/volume reaction check (day move and relative volume agree/disagree) as evidence. `news_bias(instrument_id, computed_at, score, label, evidence jsonb)`.
5. `NewsContextAdjuster` bounded −3..+3; zero with "news unavailable" when disabled or stale (> 2 h).
6. Endpoints: `GET /news?instrumentId=&from=`, `GET /context/news-bias?instrumentId=`, `GET /news/sources`, `PUT /news/sources/{id}`. Web: news bias panel with expandable source items on strategy detail and Today.

**Acceptance.** Dedupe tests; aggregation tests with fixed assessments produce expected scores/labels; `FixtureLlmProvider` classification round trip; disabled LLM → `NEUTRAL` with unavailable evidence; secrets never in `llm_call` rows.

**Verification.** `./gradlew test --tests 'money.hejje.news.*' --tests 'money.hejje.llm.*'`

---

## M3.5 Context engine, decision states, explainability, UI  (size: M)

**Goal.** Compose regime, pulse, events, and news into the Strategy Context Card (PRD §19) and the four-state decision with "Why this trade?" (PRD §15, §20).

**Tasks.**
1. `context` module: `StrategyContext{technicalFit, marketRegime, newsBias, eventRisk, sector, nextEvent, netImpact, items[] each {name, status GREEN|AMBER|RED|UNKNOWN, value, delta, evidence[]}}` built from the adjusters; `GET /context/strategy?versionId=&instrumentId=`.
2. Decision rules (document in `docs/decisions.md`): hard blocks (risk rejection, event `block`, kill switch, readiness, strategy paused) → `AVOID`; signal not active or outside window → `WAIT`; score ≥ threshold with no cautions → `TRADE`; cautions → `TRADE WITH CAUTION` where cautions are: event risk `HIGH` with `action: caution`, VIX rising > X% intraday, reward/risk fallen below strategy min but above global min, regime `avoid` preference, news bias opposing direction ≤ −0.4, market data stale < N s. Each caution is an evidence item.
3. "Why this trade" object: `supporting[]` (conditions with observed values, regime, breadth, sector, similar-regime performance) and `risks[]` (cautions and near-blocks), rendered by templates; no free text generation.
4. UI: Today header (regime, breadth, event risk), Best Hejje card with adjustment rows (PRD §8.2), ranked table `Status` now includes Caution, strategy detail Context Card with expandable evidence, Pulse screen linked; TUI best card shows context lines.
5. Degradation test: stop news/events/regime beans → Today still renders with `UNKNOWN` rows; execution unaffected.

**Acceptance.** Decision-table tests for every rule; e2e in PAPER shows a `TRADE WITH CAUTION` when an event is injected; all context beans disabled → trading e2e from Phase 2 still passes.

**Verification.** `./gradlew test --tests 'money.hejje.context.*'` and `cd web && npm run e2e`

---

## Phase 3 exit checklist

- [ ] Today shows regime/breadth/event/news adjustments with evidence.
- [ ] Every session in the historical store is regime-labeled; strategy detail shows similar-regime performance.
- [ ] With LLM, news, and events disabled, Phase 2 exit tests still pass unchanged.
- [ ] `docs/regime.md`, `docs/decisions.md`, `docs/hejje-score.md` updated for the new adjusters.
