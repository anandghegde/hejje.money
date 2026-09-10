# Configuration reference

All settings can be provided as environment variables (`HEJJE_*`) or in `application.yml`.
Secrets are environment variables only.

| Key | Env var | Default | Description |
|---|---|---|---|
| `hejje.mode` | `HEJJE_MODE` | `PAPER` | Global execution mode: `PAPER`, `CONFIRM`, `AUTO`. `CONFIRM`/`AUTO` refuse to start unless the `prod` profile is active. |
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
| `hejje.broker.adapter` | `HEJJE_BROKER_ADAPTER` | `fake` | Which `BrokerAdapter` bean is active: `fake` (deterministic in-memory broker, dev/test) or `zerodha` (M1.2). |
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
| `hejje.regime.classifier-version` | — | `1` | Stored with every label; bump after changing a rule or threshold to relabel history. |
| `hejje.regime.label-on-startup` | — | `true` (`false` in `test`) | Label sessions of the daily history that have no label under the current version after boot. |
| `hejje.regime.index-symbol` / `vix-symbol` | — | `INDEX:NIFTY 50` / `INDEX:INDIA VIX` | Instruments the trend/opening/structure and volatility rules read. |
| `hejje.regime.universe` | — | `classpath:universe/nifty50.yaml` | YAML with a `symbols:` list for breadth (a `file:` path overrides the bundled copy). |
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
| `hejje.news.enabled` | `HEJJE_NEWS_ENABLED` | `false` | News polling and classification (`docs/news.md`). |
| `hejje.news.poll-minutes` | — | `5` | Poll interval. |
| `hejje.news.sources` / `aliases` | — | `classpath:news-sources.yaml` / `classpath:aliases.yaml` | Feed list and instrument alias map (`file:` paths override the bundled copies). |
| `hejje.news.window` / `half-life` / `stale-after` | — | `24h` / `4h` / `2h` | Bias window, recency decay, and the age of the last successful poll after which the bias is unavailable. |
| `hejje.news.min-relevance` / `strong-score` / `mild-score` | — | `0.3` / `0.6` / `0.2` | Contribution threshold and label cut-offs. |
| `hejje.news.max-items-per-poll` / `title-similarity` / `fetch-timeout` | — | `25` / `0.8` / `30s` | Classification cap per poll, dedupe similarity, HTTP timeout. |
| `hejje.recommend.min-score` | `HEJJE_RECOMMEND_MIN_SCORE` | `70` | (now overridable by env; the e2e stack sets 0 so Today carries a decision without a backtest history). |
| `hejje.recommend.caution.vix-rise-pct` / `news-opposing-score` | — | `5` / `0.4` | PRD 15 caution thresholds (`docs/decisions.md`); the stale-quote caution uses `hejje.market.quote-stale-after`. |
