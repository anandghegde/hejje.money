# Configuration reference

All settings can be provided as environment variables (`HEJJE_*`) or in `application.yml`.
Secrets are environment variables only.

| Key | Env var | Default | Description |
|---|---|---|---|
| `hejje.mode` | `HEJJE_MODE` | `PAPER` | Global execution mode: `PAPER`, `CONFIRM`, `AUTO`, `SIM`. `CONFIRM`/`AUTO` refuse to start unless the `prod` profile is active; `SIM` needs the `sim` or `dev` profile (never `prod`) and `hejje.broker.adapter=fake` (docs/simulation.md). |
| `hejje.bots.min-sim-sessions` | — | `20` | SIM session reports (with positive expectancy over all their trades) a bot version needs before its strategy may be deployed in PAPER (docs/bots.md). |
| `hejje.sim.decision-timeout` | — | `PT5S` | SIM only: how long the replay waits for a connected bot's answer to a decision point before recording it SKIPPED (docs/bots.md). |
| `hejje.sim.start` | `HEJJE_SIM_START` | now | SIM only: the instant the simulation clock starts at (ISO-8601, e.g. `2026-09-08T03:45:00Z` = 09:15 IST). |
| `hejje.timezone` | — | `Asia/Kolkata` | Business time zone for session logic. |
| `hejje.data-dir` | `HEJJE_DATA_DIR` | `./data` | Directory for server-owned files (parquet, backups). |
| `spring.datasource.url` | `HEJJE_DB_URL` | `jdbc:postgresql://localhost:5432/hejje` | PostgreSQL JDBC URL. |
| `spring.datasource.username` | `HEJJE_DB_USER` | `hejje` | Database user. |
| `spring.datasource.password` | `HEJJE_DB_PASSWORD` | `hejje` | Database password. |
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | `dev` | `dev` (plain logs), `test`, `prod` (JSON logs). |
| `spring.threads.virtual.enabled` | — | `true` | Virtual threads for request handling. |
| `hejje.auth.admin-password` | `HEJJE_ADMIN_PASSWORD` | — | Bootstrap password for `admin`, read only when no user exists. Required in `prod` on first start. |
| `hejje.auth.jwt-secret` | `HEJJE_JWT_SECRET` | — | HS256 secret, at least 32 bytes. Required in `prod`; otherwise a random per-start secret is used. |
| `hejje.auth.access-token-ttl` | — | `15m` | JWT lifetime. |
| `hejje.auth.refresh-token-ttl` | — | `12h` | Refresh cookie lifetime. |
| `hejje.auth.rate-limit.default-rate` / `default-burst` | — | `20` / `40` | Per-principal requests per second and bucket size. |
| `hejje.auth.rate-limit.transactional-rate` / `transactional-burst` | — | `5` / `5` | Limits for non-GET calls under `transactional-paths`. |
| `hejje.auth.rate-limit.transactional-paths` | — | `/api/v1/orders, /api/v1/positions, /api/v1/risk, /api/v1/auth/clients` | Path prefixes that count as transactional. |
| `hejje.execution.expected-ips` | `HEJJE_EXECUTION_EXPECTED_IPS` | (empty) | Comma-separated public IPs registered with the broker. Empty means `MISMATCH` when verification is enabled. |
| `hejje.system.egress.enabled` | — | `true` (`false` in `dev`/`test`) | Egress IP verification; disabled reports `SKIPPED`. |
| `hejje.system.egress.check-interval` | — | `5m` | How often the public IP is resolved. |
| `hejje.system.egress.resolvers` | — | `https://api.ipify.org, https://checkip.amazonaws.com` | Plain-text public-IP endpoints. |
| `hejje.system.egress.timeout` | — | `5s` | Per-resolver HTTP timeout. |
| `hejje.system.clock.enabled` | — | `true` (`false` in `dev`/`test`) | Clock drift check; disabled reports `SKIPPED`. |
| `hejje.system.clock.check-interval` | — | `10m` | How often the remote clock is compared. |
| `hejje.system.clock.host` | — | `https://www.google.com` | HTTPS URL whose `Date` header is the reference. |
| `hejje.system.clock.max-drift` | — | `2s` | Drift above which the clock is `DEGRADED` (blocks execution). |
| `management.server.port` | — | (same as server) / `8081` in `prod` | Actuator and Prometheus port. |
| `hejje.broker.adapter` | `HEJJE_BROKER_ADAPTER` | `fake` | Which `BrokerAdapter` bean is active: `fake` (deterministic in-memory broker, dev/test), `zerodha` (M1.2) or `dhan` (M5.6, `docs/broker-dhan.md`). |
| `hejje.instruments.sync-on-startup` | — | `false` (`true` in `dev`) | Run the instrument master sync once after startup. |
| `hejje.instruments.sync-cron` | — | `0 0 8 * * MON-FRI` | Cron (IST) of the daily instrument sync; skipped on exchange holidays. |
| `hejje.broker.web-url` | `HEJJE_WEB_URL` | `http://localhost:5173` | Web client base URL; the broker login callback redirects there. |
| `hejje.broker.zerodha.api-key` | `HEJJE_KITE_API_KEY` | — | Kite Connect app key. Required when the adapter is `zerodha`. |
| `hejje.broker.zerodha.api-secret` | `HEJJE_KITE_API_SECRET` | — | Kite Connect app secret. Required when the adapter is `zerodha`; also enables the postback endpoint. |
| `hejje.broker.zerodha.base-url` | — | `https://api.kite.trade` | Kite REST base URL (tests point it at WireMock). |
| `hejje.broker.zerodha.connect-timeout` / `read-timeout` | — | `5s` / `10s` | HTTP timeouts; a timed-out transactional call is `TIMEOUT` (outcome unknown). |
| `hejje.broker.fake.connected` | — | `true` | Fake broker starts with a connected session. |
| `hejje.broker.fake.starting-capital` | — | `1000000` | Fake broker cash in rupees. |
| `hejje.security.encryption-key` | `HEJJE_ENCRYPTION_KEY` | — | 32 bytes (base64 or hex) for AES-256-GCM of broker tokens at rest. Required in `prod`; random per start otherwise. |
| `hejje.market.watchlist` | — | NIFTY 50, NIFTY BANK, INDIA VIX (+ nearest NIFTY/BANKNIFTY futures) | Canonical symbols streamed in FULL mode by default. |
| `hejje.market.stream-on-startup` | `HEJJE_MARKET_STREAM` | `false` (`true` in `dev`) | Open the broker stream and subscribe the watchlist once the session is connected. |
| `hejje.market.stale-after` | — | `10s` | No tick for this long during the session makes `marketData` readiness STALE. |
| `hejje.market.quote-stale-after` | — | `5s` | QuoteCache staleness threshold. |
| `hejje.market.tick-queue` | — | `100000` | Tick-bus bounded queue; overflow drops the oldest (`hejje_tick_bus_dropped_total`). |
| `hejje.market.record` | `HEJJE_MARKET_RECORD` | `false` | Record ticks to `data-dir/ticks/{date}/ticks.parquet`. |
| `hejje.market.retention-sessions` | — | `15` | Postgres candle retention in trading sessions (older stays in Parquet). |
| `hejje.market.historical-per-second` | — | `3` | Historical backfill throttle until the M1.6 rate limiter. |

Risk limits are not application properties; they are rows in `risk_limits` (one per mode, seeded with defaults in
migration V9) and edited through `PUT /api/v1/risk/limits`. See `docs/risk.md`.
| `hejje.broker.limits.orders-per-second` / `-per-minute` / `-per-day` | — | `10` / `200` / `3000` | Broker order rate limits (PRD 39); transactional calls fail fast with RATE_LIMITED when exhausted. |
| `hejje.broker.limits.quote-per-second` | — | `1` | Quote request rate. |
| `hejje.broker.limits.historical-per-second` | — | `3` | Historical request rate. |
| `hejje.broker.limits.general-per-second` | — | `10` | Rate for all other broker calls. |
| `hejje.broker.limits.read-wait-millis` | — | `1000` | How long a read may wait for a rate-limit token before failing. |
| `hejje.reconciliation.pause-on-critical` | — | `true` | Trip the kill switch (STOP_NEW_ORDERS) on a CRITICAL reconciliation issue. |
| `hejje.paper.slippage-bps` | `HEJJE_PAPER_SLIPPAGE_BPS` | `5` | MARKET fills slip this many basis points against the taker (PAPER mode). |
| `hejje.paper.partial-fill-probability` | — | `0.0` | Chance a paper fill is split (0 disables). |
| `hejje.paper.starting-capital` | — | `1000000` | Simulated paper cash in rupees. |
| `hejje.costs.*` | — | see config/costs.yaml | Transaction cost rates (brokerage, STT, exchange txn, GST, SEBI, stamp duty) per segment. |
| `hejje.strategy.load-bundled` | — | `true` | Load `strategies/*.yaml` at startup (new slugs created, changed definitions become new versions). |
| `hejje.strategy.bundled-dirs` | — | `./strategies, ../strategies` | Directories searched for bundled definitions; the first that exists wins, otherwise the copies packaged in the jar (`classpath:strategies/`). |
| `hejje.strategy.aliases` | — | `NIFTY`, `BANKNIFTY`, `FINNIFTY` → `nearest_future: <name>` | Bare universe names allowed in definitions and what they resolve to (a symbol, `nearest_future: X` or `index: X`). |
| `hejje.backtest.workers` | — | `2` | Concurrent backtests. |
| `hejje.backtest.warmup-days` | — | `20` | Calendar days of candles loaded before `from` to warm indicators up. |
| `hejje.backtest.default-risk-rupees` | — | `2000` | Money risked per trade when neither the spec nor the definition says. |
| `hejje.backtest.max-trades-per-backtest` | — | `200000` | Safety cap on persisted trades per run. |
| `hejje.signals.enabled` | — | `true` | Start the signal engine (runners for enabled deployments) at startup. |
| `hejje.signals.warmup-days` | — | `10` | Calendar days of candles a runner loads to warm its indicators up. |
| `hejje.signals.default-validity-minutes` | — | `0` | Signal validity when the definition has none; `0` = until the next bar closes. |
| `hejje.signals.expiry-sweep` | — | `30s` | How often stale ACTIVE/PREPARED signals are expired. |
| `hejje.signals.inline-dispatch` | — | `false` (`true` in `test`) | Process bus and order events on the calling thread instead of the engine thread. |
| `hejje.signals.default-risk-rupees` | — | `2000` | Money risked per signal when neither the deployment (`params.risk_rupees`) nor the definition says. |
| `hejje.recommend.min-score` | — | `70` | Hejje Score a valid signal needs to be recommended as TRADE (else WAIT). |
| `hejje.market.dev-candles` | — | `false` (`true` in `dev`/`test`) | Enable `POST /market/dev/candles` (scripted sessions for development). |
| `hejje.strategy.allow-forced-status` | — | `false` (`true` in `dev`/`test`) | Honour `force: true` on version status changes (bypasses lifecycle evidence; audited as forced). |
| `hejje.execution.allow-off-session-paper` | — | `false` (`true` in `dev`) | Let PAPER-mode intents through outside market hours. Live modes are never exempt. |
| `hejje.regime.enabled` | — | `true` | Regime engine (plan M3.1, `docs/regime.md`). Off: every label `UNKNOWN`, nothing stored, adjuster 0. |
| `hejje.regime.classifier-version` | — | `2` | Stored with every label; bump after changing a rule or threshold to relabel history. |
| `hejje.regime.label-on-startup` | — | `true` (`false` in `test`) | Label sessions of the daily history that have no label under the current version after boot. |
| `hejje.regime.index-symbol` / `vix-symbol` | — | `INDEX:NIFTY 50` / `INDEX:INDIA VIX` | Instruments the trend/opening/structure and volatility rules read. |
| `hejje.regime.universe` | — | `classpath:universe/nifty50.yaml` | YAML with a `symbols:` list for breadth (a `file:` path overrides the bundled copy). |
| `hejje.regime.market-condition.*` | — | see `config/regime.yaml` | Market condition thresholds (plan M8.3, `docs/regime.md`): `distribution-drop-pct 0.2`, `distribution-window 25`, `distribution-expiry-gain-pct 5`, `follow-through-min-day 4`, `follow-through-gain-pct 1.25`, `pressure-count 4`, `downtrend-count 6`, `downtrend-below-sma-count 5`, `recover-count 3`, `sma 50`, `window-sessions 200`, `min-constituents 45` (`4` in `test`). |
| `hejje.regime.lookback-sessions` / `min-sessions` | — | `250` / `60` | Percentile window and the minimum history before volatility is labelled. |
| `hejje.regime.intraday-snapshot` | — | `PT5M` | Snapshot cache lifetime and the interval of stored intraday snapshots during the session. |
| `hejje.regime.trend.*`, `volatility.*`, `opening.*`, `breadth.*`, `structure.*` | — | see `config/regime.yaml` | Rule thresholds, documented in `docs/regime.md`. |
| `hejje.regime.adjuster.*` | — | `preferred-points 3`, `avoid-points 6`, `similar-full-points 7`, `similar-full-diff-r 0.5`, `min-similar-trades 10` | Points of the "Current regime" score adjuster (`docs/hejje-score.md`). |
| `hejje.pulse.enabled` | — | `true` | Pulse (plan M3.2, `docs/pulse.md`). Off: NEUTRAL/WEAK with score 0 and a "disabled" evidence line; nothing stored. |
| `hejje.pulse.sectors` | — | `classpath:universe/sectors.yaml` | YAML `sectors: [{name, symbol}]` of the Market Pulse rows. |
| `hejje.pulse.index-symbol` / `vix-symbol` / `futures-underlying` | — | `INDEX:NIFTY 50` / `INDEX:INDIA VIX` / `NIFTY` | Inputs of the Technical Pulse (the nearest future of the underlying gives relative volume and basis). |
| `hejje.pulse.interval` | — | `PT1M` | Recompute/store cadence during the session and the cache lifetime of `GET /context/pulse`. |
| `hejje.pulse.weights.*` | — | see `config/pulse.yaml` | Rule weights (`index_trend 20, index_vs_vwap 15, day_change 10, momentum 10, breadth 15, relative_volume 5, vix 10, sectors 10, futures_basis 5, gap 5`). |
| `hejje.pulse.thresholds.*` | — | see `config/pulse.yaml` | Rule thresholds and the direction/strength cut-offs (`docs/pulse.md`). |
| `hejje.ratings.enabled` | — | `false` (`true` in `test`) | Daily context layer (plan Phase 8, `docs/ratings.md`): evening D1 refresh, ratings, groups, bases, lists. Off: nothing is fetched or computed and `/api/v1/ratings/**` answers 503. |
| `hejje.ratings.universe` | — | `nifty500` | Universe file name under `config/universe` (D1 only). |
| `hejje.ratings.engine-version` | — | `1` | Stored with every row; bump after changing a formula or threshold (old rows are kept, new rows are written). |
| `hejje.ratings.daily-refresh-cron` | — | `0 30 18 * * MON-FRI` (IST) | When the evening D1 refresh runs; it publishes `DailyCandlesRefreshed`, which the nightly computations chain off. Skipped in SIM. |
| `hejje.ratings.refresh-sessions` | — | `5` | Trading days of D1 candles the evening refresh re-fetches (an upsert). |
| `hejje.ratings.formula.*` | — | see `config/ratings.yaml` | Windows and weights of the ratings (`quarter-sessions 63`, `rs-weights 0.4/0.2/0.2/0.2`, `ad-sessions 65`, `high-low-sessions 252`, `volume-sessions 50`, `min-group-members 3`, `composite-weights`, `min-session-coverage 0.2`). |
| `hejje.ratings.bases.*` | — | see `config/ratings.yaml` | Base detector, trade plan and lifecycle thresholds (`docs/ratings.md`, "Bases"): prior uptrend 25 % over 120 sessions, flat base 25–65 sessions ≤ 15 %, cup 35–325 sessions 12–35 % with 90 % recovery, handle 5–30 sessions ≤ 12 %, double bottom lows within 3 %, buy zone 5 %, stop 7 %, goal 20 % (reversal 8 %), breakout volume 1.4×, expiry 60 / max hold 120 sessions. |
| `hejje.ratings.lists.*` | — | see `config/ratings.yaml` | List thresholds: `leader-composite 85`, `leader-rs 80`, `min-turnover-cr 10`, `mover-change-pct 2`, `mover-volume 1.5`. |
| `hejje.analogs.enabled` | — | `false` (`true` in `test`) | Historical analogs (plan M8.5/M8.6, `docs/analogs.md`). Off: nothing is computed and `/api/v1/analogs/**` answers 503. |
| `hejje.analogs.universe` / `engine-version` | — | `nifty500` / `1` | Daily universe file; the engine version stored with every summary (bump after changing a weight, band or threshold). |
| `hejje.analogs.lookbacks` / `forwards` | — | `5,10,15,20,25,30,40,50` / `3,5,10,15` | Window lengths and forward windows in sessions. Cut lookbacks here if the nightly run exceeds its budget. |
| `hejje.analogs.max-matches` / `max-distance` / `min-evidence` | — | `50` / `1.5` / `10` | Matches kept per symbol and lookback, the similarity cap, and the count below which the direction is `INSUFFICIENT`. |
| `hejje.analogs.parallelism` / `nightly-budget` / `match-retention-days` | — | `4` / `PT30M` / `30` | Worker threads of a run, the budget a nightly run is measured against (a warning when exceeded), and how long match documents are kept. |
| `hejje.analogs.weights.*`, `prefilter.*`, `tags.*` | — | see `config/analogs.yaml` | Similarity weights (shape-heavy), prefilter bands in universe standard deviations, and tag thresholds (`docs/analogs.md`). |
| `hejje.analogs.session.*` | — | see `config/analogs.yaml` | Session analogs: `universe nifty50`, `extra-symbols` (the two indices), `checkpoints 09:45,10:15,11:15,13:00`, `exit-time 15:10`, `context-weight 0.10`, `percent-scale 1.0`, `volume-sessions 20`. |
| `hejje.market.watchlist` | — | NIFTY 50, NIFTY BANK, INDIA VIX and the sector indices of `config/universe/sectors.yaml` | Symbols streamed in FULL mode (M3.2 added the sector indices). |
| `hejje.events.enabled` | — | `true` | Event calendar and event risk (plan M3.3, `docs/events.md`). Off: no events, risk `LOW` with an "unavailable" line, rules not applied. |
| `hejje.events.refresh-on-startup` | — | `true` (`false` in `test`) | Pull every enabled source after boot (also daily at 07:00 IST). |
| `hejje.events.horizon-days` | — | `60` | Refresh window ahead and the next-event search horizon. |
| `hejje.events.risk.macro-high-within-minutes` / `macro-in-progress-minutes` / `earnings-heavy-count` | — | `60` / `30` / `5` | Proximity thresholds (`docs/events.md`). |
| `hejje.events.computed.enabled` / `expiry-underlyings` / `index-rebalance-dates` | — | `true` / `NIFTY, BANKNIFTY` / (empty) | Holidays, expiries and rebalance dates. |
| `hejje.events.curated.enabled` / `files` | — | `true` / `classpath:events/macro-2026.yaml` | Curated macro calendars (`file:` paths override the bundled copy). |
| `hejje.events.nse.enabled` / `base-url` / `timeout-seconds` | — | `false` / `https://www.nseindia.com` / `5` | Optional best-effort NSE corporate-action fetcher. |
| `hejje.llm.enabled` | `HEJJE_LLM_ENABLED` | `false` | LLM provider abstraction (`docs/llm.md`). Off: every call is `Unavailable`; callers degrade. |
| `hejje.llm.providers.<name>.*` | — | `primary`: `openai-compatible`, `base-url` `HEJJE_LLM_BASE_URL`, `api-key-env` `HEJJE_LLM_API_KEY`, `model` `HEJJE_LLM_MODEL` | Providers (`type` `openai-compatible`/`gemini`/`fixture`, `base-url` (Gemini defaults to Google's), `api-key-env`, `model`, `timeout`). The key is read from the named env var only. |
| `hejje.llm.profiles.<fast|reasoning|news|research>` | — | all → `primary` | Profile → provider, optional model/temperature/max-tokens. |
| `hejje.llm.retries` / `backoff` | — | `2` / `500ms` | Retries on timeouts, 429 and 5xx; backoff doubles. |
| `hejje.llm.pricing.<model>` | — | (none) | Rupees per million input/output tokens for the call-log cost estimate. |
| `hejje.llm.daily-cost-cap` | `HEJJE_LLM_DAILY_COST_CAP` | (none) | Rupees per IST day, summed from the call log's cost estimates (calls without `pricing` count as zero). Reaching it disables every LLM call until the next IST day and records one `LLM_BUDGET_EXCEEDED` audit alert. |
| `hejje.llm.circuit-breaker.failure-threshold` / `open-for` | — | `5` / `60s` | Consecutive retryable failures (timeouts, 429, 5xx) that open a provider's circuit, and how long it stays open before one trial call. |
| `hejje.llm.profiles.<name>.fallback` | — | (none) | Profile tried once when this profile's provider fails or its circuit is open (not after streaming has started). |
| `hejje.llm.dev-fixture-endpoint` | — | `false` (`true` in dev/test) | Enables `POST /agents/llm/dev/fixture` (canned answers on a `type: fixture` provider). |
| `hejje.jev.enabled` | `HEJJE_JEV_ENABLED` | `false` | Jev typed decisions (`docs/jev.md`), independent of `hejje.llm`. Off: every call answers `DISABLED` and nothing is recorded; callers fall back. |
| `hejje.jev.base-url` | `HEJJE_JEV_BASE_URL` | `https://api.surplusintelligence.ai` | API root (`/v1/decisions` is appended); `fixture` selects the scripted in-process fixture (the `test` profile). |
| `hejje.jev.api-key-env` | — | `HEJJE_JEV_API_KEY` | Env var holding the TypeSafe key; the key is only ever put in the Authorization header. |
| `hejje.jev.model` | — | `jev-1.13` | Pinned model version (calibration is per version; the `jev-latest` alias moves on a release). |
| `hejje.jev.timeout` | — | `2500ms` | Default deadline of one evaluation, retry included. |
| `hejje.jev.max-questions-per-call` | — | `120` | A larger question set throws (programming error). |
| `hejje.jev.input-per-million` | — | `3.6` | Rupees per million input tokens for the cost estimate (output tokens are free). |
| `hejje.jev.daily-cost-cap` | `HEJJE_JEV_DAILY_COST_CAP` | (none) | Rupees per IST day; reaching it answers `BUDGET` until the next day and records one `JEV_BUDGET_EXCEEDED` audit event. |
| `hejje.jev.circuit-breaker.failure-threshold` / `open-for` | — | `5` / `60s` | Consecutive retryable failures that open the Jev circuit, and for how long (`JEV_CIRCUIT_OPEN` when it opens). |
| `hejje.jev.sim-cache` | — | `true` | In SIM, a state already answered with the same question set version is answered from the store (no call, no cost). |
| `hejje.jev.state-retention-days` | — | `30` | Days the sent state is kept in `jev_state`; answers are kept. |
| `hejje.jev.knowledge-cutoff` | `HEJJE_JEV_KNOWLEDGE_CUTOFF` | (none) | Release date of the pinned model: the knowledge cutoff of JEV bots (SIM days before it are flagged). A JEV bot registers only with this or an explicit `knowledgeCutoff`. |
| `hejje.jev.signal-check.enabled` | `HEJJE_JEV_SIGNAL_CHECK` | `false` | Ask Jev about every strategy signal and annotate it (docs/signals.md). |
| `hejje.jev.signal-check.gate` | — | `off` | `off`, `caution` or `approval`; above `off` the application refuses to start until calibration of `signal-check` passes. |
| `hejje.jev.signal-check.question-set` | — | `bot-stage2` | The question set the check asks. |
| `hejje.risk.macro-event-size-factor` | — | `1.0` | Risk-event size cut (docs/risk.md): on a session with a market-wide macro event, new entries of signals and bots risk this share of their risk money (1.0 = off; must be in (0, 1]); the backtester applies it too. |
| `hejje.analytics.cause.*` | — | `config/analytics.yaml` | Trade-cause thresholds (docs/analytics.md "Trade cause"): `extended-atr` 1.5, `vwap-atr` 2.0, `clean-target-mae-r` −0.5, `noise-recovery-r` 1.0, `drift-r` 0.3, `early-mae-r` −0.7, `early-mfe-r` 0.5, `late-mfe-r` 0.3, `late-range-share` 0.2, `pre-entry-minutes` 15, `range-minutes` 30, `post-exit-minutes` 30. |
| `hejje.calibration.entry-horizon-minutes` / `direction-horizon-minutes` / `exit-horizon-minutes` | — | `30` / `60` / `15` | Outcome windows of the calibration rules (`docs/calibration.md`); pre-registered, change only with a note there. |
| `hejje.calibration.min-bucket-count` | — | `20` | A probability bucket with fewer labelled answers shows its count and no rate. |
| `hejje.calibration.min-labelled` / `min-sessions` / `max-ece` | — | `300` / `15` / `0.07` | The pass bar (with the top/bottom bucket separation) that gates on Jev numbers check. |
| `hejje.calibration.no-data-after-days` | — | `5` | A prediction still without candles in its window this long after it is labelled NONE. |
| `hejje.agent.ai.enabled` | — | `true` | Hejje AI chat (also needs `hejje.llm.enabled`). |
| `hejje.agent.ai.profile` / `follow-up-profile` | — | `reasoning` / `fast` | LLM profile for the first question of a conversation / later questions. |
| `hejje.agent.ai.max-steps` | — | `8` | LLM steps per question before the tool loop stops. |
| `hejje.agent.ai.max-tool-result-chars` | — | `12000` | Characters of each tool result the model sees. |
| `hejje.agent.ai.history-turns` | — | `10` | Earlier question/answer pairs replayed to the model. |
| `hejje.agent.approvals.ttl` | — | `5m` | How long an approval stays open (signal proposals expire with the signal instead). |
| `hejje.agent.approvals.account-autonomy-level` | — | `3` | Autonomy level (0–3) for agent proposals not tied to a deployed strategy. |
| `hejje.agent.approvals.expiry-sweep` | — | `30s` | How often expired approvals are marked EXPIRED (also done whenever approvals are read or decided). |
| `hejje.backtest.experiments.parallelism` | — | `2` | Experiment variants backtested at once. |
| `hejje.backtest.experiments.max-variants` | — | `12` | Variants per experiment (baseline not counted). |
| `hejje.execution.planning.leg-timeout` | — | `30s` | Longest a basket leg or split child is waited on (besides the deadline). |
| `hejje.execution.planning.max-basket-legs` | — | `20` | Legs per basket. |
| `hejje.execution.planning.basket-deadline` / `split-deadline` | — | `15m` / `15m` | Default deadlines. |
| `hejje.execution.planning.auto-split-above` / `auto-split-child-quantity` / `auto-split-delay` | — | `0` (off) / `0` (= the threshold) / `1s` | Automatic splitting of large intents. |
| `hejje.options.risk-free-rate` / `default-volatility` | — | `0.065` / `0.15` | Black-76 rate; volatility for delta-based strike selection when a strike has no IV. |
| `hejje.options.max-lots` / `max-premium-rupees` | — | `10` / `50000` | Options risk: lots per order; premium at risk per buy. |
| `hejje.options.expiry-day-cutoff` | — | `13:00` | No new option positions on their expiry day from this IST time (and strike selection skips today's expiry). |
| `hejje.options.min-paper-trades` | — | `30` | Closed paper options positions a version needs before LIVE. |
| `hejje.options.monitor-interval` | — | `5s` | Options position checks (exits). |
| `hejje.options.underlyings` | — | `NIFTY 50: NIFTY`, `NIFTY BANK: BANKNIFTY`, `NIFTY FIN SERVICE: FINNIFTY` | Index → option underlying for signals on an index. |
| `hejje.auto.acknowledged` | `HEJJE_AUTO_ACKNOWLEDGED` | `false` | Required (with the `prod` profile) for `hejje.mode=AUTO`; startup fails otherwise. |
| `hejje.auto.min-paper-trades` | — | `30` | Closed paper trades a version needs before an AUTO deployment at autonomy 4-5 ("a new strategy version is never automatic"). |
| `hejje.auto.default-max-trades-per-day` / `default-max-loss-rupees` | — | `3` / `5000` | Per-deployment daily budget at autonomy 4-5 (entries; gross realized loss); deployment params `daily_max_trades` / `daily_max_loss_rupees` override. |
| `hejje.auto.self-pause-drift` | — | `DEGRADING` | Drift status at which an autonomy-5 deployment pauses itself. |
| `hejje.auto.sweep` | — | `30s` | Re-offers actionable signals of autonomy 4-5 deployments (also on startup). |
| `hejje.drift.enabled` | — | `true` | Live-vs-backtest drift monitor (`docs/analytics.md`); thresholds and actions live in `config/drift.yaml`. |
| `hejje.drift.interval` | — | `10m` | Sweep over enabled deployments (each reviewed strategy trade also re-evaluates its deployment). |
| `hejje.drift.trailing-trades` / `trailing-sessions` / `min-trades` | — | `30` / `60` / `10` | Newest trades compared, within this many sessions; fewer than `min-trades` is INSUFFICIENT_DATA. |
| `hejje.drift.bootstrap-samples` / `confidence` | — | `2000` / `0.90` | Bootstrap interval of the live expectancy (fixed seed, deterministic). |
| `hejje.drift.thresholds.{watch,degrading,failed}` | — | see `config/drift.yaml` | `win-rate-p-value`, `expectancy-ratio`, `expectancy-upper-ratio`, `expectancy-upper-r`, `drawdown-multiple`; any met criterion reaches the level. |
| `hejje.drift.actions.<STATUS>` | — | WATCH `[ALERT, LOWER_SCORE]`, DEGRADING `+ REDUCE_SIZE`, FAILED `[ALERT, LOWER_SCORE, PAUSE]` | Actions per status: `ALERT`, `LOWER_SCORE`, `REDUCE_SIZE`, `MOVE_TO_PAPER`, `PAUSE`. |
| `hejje.drift.size-multiplier` / `score-points` | — | `0.50` / WATCH −5, DEGRADING −10, FAILED −15 | What REDUCE_SIZE sets; the drift adjuster's points (clamped to −15..0). |
| `hejje.news.enabled` | `HEJJE_NEWS_ENABLED` | `false` | News polling and classification (`docs/news.md`). |
| `hejje.news.poll-minutes` | — | `5` | Poll interval. |
| `hejje.news.sources` / `aliases` | — | `classpath:news-sources.yaml` / `classpath:aliases.yaml` | Feed list and instrument alias map (`file:` paths override the bundled copies). |
| `hejje.news.window` / `half-life` / `stale-after` | — | `24h` / `4h` / `2h` | Bias window, recency decay, and the age of the last successful poll after which the bias is unavailable. |
| `hejje.news.min-relevance` / `strong-score` / `mild-score` | — | `0.3` / `0.6` / `0.2` | Contribution threshold and label cut-offs. |
| `hejje.news.max-items-per-poll` / `title-similarity` / `fetch-timeout` | — | `25` / `0.8` / `30s` | Classification cap per poll, dedupe similarity, HTTP timeout. |
| `hejje.news.classifier` | `HEJJE_NEWS_CLASSIFIER` | `llm` | `llm`, `jev` or `shadow` (LLM used, Jev stored beside it for `GET /news/classifier-comparison`); docs/news.md. |
| `hejje.news.risk-event-threshold` / `market-headline-count` | — | `0.6` / `15` | Index risk events from headlines (Jev): P(risk event today) that records a market-wide macro event, and how many of the day's newest titles are read. |
| `hejje.recommend.min-score` | `HEJJE_RECOMMEND_MIN_SCORE` | `70` | (now overridable by env; the e2e stack sets 0 so Today carries a decision without a backtest history). |
| `hejje.recommend.caution.vix-rise-pct` / `news-opposing-score` | — | `5` / `0.4` | PRD 15 caution thresholds (`docs/decisions.md`); the stale-quote caution uses `hejje.market.quote-stale-after`. |
| `hejje.notify.enabled` | — | `true` | Notifications (`docs/notifications.md`); off creates none. |
| `hejje.notify.high-score` | — | `80` | A signal at or above this Hejje Score is also a `HIGH_SCORE_SETUP`. |
| `hejje.notify.event-lead` / `dedupe-window` / `digest-interval` | — | `60m` / `10m` / `5m` | Warning ahead of HIGH-risk events; repeats with the same key skipped; rate-limited notifications sent as one digest. |
| `hejje.notify.email.enabled` / `from` / `to` / `per-minute` | `HEJJE_NOTIFY_EMAIL_ENABLED` / `…_FROM` / `…_TO` | `false` / — / — / `5` | Email channel; SMTP from `spring.mail.host`, `port`, `username`, `password` (`SPRING_MAIL_*`). |
| `hejje.notify.telegram.enabled` / `chat-id` / `per-minute` / `timeout` | `HEJJE_NOTIFY_TELEGRAM_ENABLED` / `…_CHAT_ID` | `false` / — / `20` / `10s` | Telegram channel. |
| `hejje.notify.telegram.bot-token` | `HEJJE_TELEGRAM_BOT_TOKEN` | — | Bot token: env only, never in a config file, the API or a log. |
| `hejje.webhooks.replay-window` | — | `5m` | Allowed distance between a webhook's timestamp and the server clock (`docs/webhooks.md`). |
| `hejje.webhooks.signal-validity` | — | `5m` | How long an external signal stays actionable. |
| `hejje.webhooks.default-risk-rupees` | — | `2000` | Sizing of MANUAL_EXTERNAL intents without `riskRupees`. |
| `hejje.broker.dhan.client-id` / `app-id` / `app-secret` | `HEJJE_DHAN_CLIENT_ID` / `HEJJE_DHAN_APP_ID` / `HEJJE_DHAN_APP_SECRET` | — | Dhan adapter: client id (required with `adapter=dhan`); API key and secret for the consent login (optional, env only). |
| `hejje.broker.dhan.base-url` / `auth-url` / `instruments-url` | — | `https://api.dhan.co/v2` / `https://auth.dhan.co` / the compact security master | Dhan endpoints. |
| `hejje.broker.dhan.quote-poll-interval` / `connect-timeout` / `read-timeout` | — | `2s` / `5s` / `10s` | Market data by REST quote polling; HTTP timeouts. |
| `hejje.broker.rate-limits.<broker>.*` | — | zerodha 10/s, 200/min, 3000/day orders; dhan 10/s, 250/min, 7000/day orders, quotes 1/s, data 5/s, other 20/s | Per-broker limits (same fields as `hejje.broker.limits`, which applies to a broker without an entry). |
| `hejje.execution.lease.ttl` / `heartbeat` | — | `30s` / `10s` | Executor lease: a standby takes over once the lease has not been renewed for `ttl`. |
| `hejje.execution.lease.instance` | `HEJJE_EXECUTION_LEASE_INSTANCE` | host name | This instance's name in status and audit. |
| `hejje.execution.lease.failover-hold` | — | `2m` | After a controlled failover the old active does not contend for the lease this long. |
