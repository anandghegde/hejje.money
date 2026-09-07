# Phase 1 — Execution Foundation

Goal of the phase (PRD §67): the authoritative execution daemon. Manual and paper trading end to end through instruments → broker adapter → market data → order intents → risk → execution → reconciliation, with a basic Web client and TUI. No strategies yet.

Order: M1.1 → M1.2 → M1.3 → M1.4 → M1.5 → M1.6 → M1.7 → M1.8 → M1.9. M1.8 and M1.9 can run in parallel after M1.7.

---

## M1.1 Instrument master and universal symbols  (size: M)

**Goal.** Broker-neutral instruments with Zerodha mappings, synced daily.

**Tasks.**
1. Tables: `instrument(id, symbol, name, exchange, type {EQ, FUT, OPT, INDEX}, underlying, expiry date, strike numeric, option_type {CE, PE}, lot_size, tick_size, isin, active, updated_at)` with unique `(exchange, symbol, type, expiry, strike, option_type)`; `broker_instrument_mapping(instrument_id, broker, broker_token, trading_symbol, exchange_segment, raw jsonb, synced_at)` unique `(broker, broker_token)`.
2. Canonical Hejje symbol string, documented in `docs/symbols.md`: `NSE:RELIANCE`, `INDEX:NIFTY 50`, `NFO:NIFTY:FUT:2026-09-24`, `NFO:NIFTY:OPT:2026-09-24:25000:CE`. `HejjeSymbol.parse/format` with tests.
3. `InstrumentSyncJob` at 08:00 IST on trading days and via `POST /api/v1/instruments/sync` (admin): pulls the Kite instruments CSV through the adapter interface (`BrokerAdapter.getInstruments()` — define the interface method now, implement in M1.2; in this milestone read from a fixture file), upserts instruments and mappings, marks missing as inactive, audits counts.
4. `InstrumentService` public API: `findById`, `resolve(symbol)`, `search(q, exchange, type)`, `nearestFuture(underlying, asOf)`, `optionChain(underlying, expiry)` (basic), `weeklyExpiries(underlying)`.
5. Endpoints (`market:read`): `GET /instruments?q=&exchange=&type=&limit=`, `GET /instruments/{id}`, `GET /instruments/resolve?symbol=`.

**Acceptance.** Fixture CSV (≈30 rows incl. EQ, FUT, OPT, INDEX) imports; second sync is idempotent; `nearestFuture("NIFTY")` returns the correct contract given a mutable clock.

**Verification.** `./gradlew test --tests 'money.hejje.instruments.*'`

---

## M1.2 Broker adapter contract, Zerodha adapter, session lifecycle  (size: L)

**Goal.** `BrokerAdapter` interface from PRD §5.2 with a Zerodha implementation and a deterministic fake; broker session owned by the server.

**Tasks.**
1. `broker` module public API:
   - `BrokerAdapter` with `authenticate(requestToken)`, `sessionState()`, `getProfile()`, `getQuote(ids)`, `getHistory(instrument, timeframe, from, to)`, `streamMarketData(...)` (returns a handle; see M1.3), `placeOrder(BrokerOrderRequest)`, `modifyOrder`, `cancelOrder`, `getOrder`, `getOrders`, `getTrades`, `getPositions`, `getHoldings`, `getFunds`, `getInstruments()`, `getOrderMargins(...)`.
   - Hejje-side models only: `BrokerOrderRequest` (instrumentId, side, qty, orderType, product, limit/trigger price, validity, tag), `BrokerOrderRef`, `BrokerOrder` (normalized status enum), `BrokerTrade`, `BrokerPosition`, `BrokerHolding`, `Funds`, `Quote`, `Candle`.
   - `BrokerException` with `kind {AUTH, RATE_LIMIT, NETWORK, TIMEOUT, REJECTED, INPUT, UNKNOWN}`, broker message, retryable flag.
2. `broker.zerodha.ZerodhaKiteAdapter` wrapping `javakiteconnect`: enum/field mapping (variety `regular`, product MIS/CNC/NRML, order types, validity DAY/IOC), error mapping, instrument-token lookup through `InstrumentService`. `KiteProperties`: `api-key`, `api-secret` (env `HEJJE_KITE_API_KEY`, `HEJJE_KITE_API_SECRET`), timeouts.
3. Session: table `broker_session(id, broker, broker_user_id, access_token_enc, public_token, established_at, expires_at, status {CONNECTED, EXPIRED, DISCONNECTED, ERROR}, last_checked_at)`. `TokenCipher` AES-GCM with `HEJJE_ENCRYPTION_KEY`.
   - `GET /broker/login-url` → Kite login URL (user opens it in a browser).
   - `GET /broker/callback?request_token=&status=` → exchange token, persist, status `CONNECTED`, then redirect to `HEJJE_WEB_URL/broker?connected=1`.
   - `GET /broker/status` (PRD §40 shape) and `POST /broker/logout` (invalidates at Kite, clears row).
   - Scheduler: at 06:15 IST mark session `EXPIRED`; on startup and every 5 min during session call `getProfile()` to validate; on `AUTH` error → `DISCONNECTED`, publish `BrokerSessionChanged`, readiness check `brokerSession` red, audit `BROKER_DISCONNECTED`.
4. `broker.fake.FakeBrokerAdapter` (profiles `dev`, `test`): in-memory orderbook/positions/funds; fills MARKET immediately at injected quote, LIMIT/SL when an injected quote crosses; scripted failures (`failNext(kind)`, `delayNext(ms)`, `dropAck()` to simulate UNKNOWN); emits order updates through the same callback path as the real adapter.
5. `BrokerAdapter` bean selection by `hejje.broker.adapter = zerodha | fake` (default `fake` outside `prod`).
6. `docs/broker-zerodha.md`: app setup, redirect URL, daily login procedure, token lifecycle, error mapping table.

**Acceptance.**
- WireMock tests: token exchange checksum correct; place/modify/cancel/getOrders mapping; each Kite error family maps to the right `BrokerException.kind`.
- `TokenCipher` round trip; encrypted value in DB is not the plaintext.
- Log capture test proves api secret and access token never appear in logs.
- Fake adapter script tests (fill, reject, timeout).

**Verification.** `./gradlew test --tests 'money.hejje.broker.*'`

---

## M1.3 Market data: streaming, candles, quotes, historical store  (size: L)

**Goal.** Normalized ticks and candles from Kite, candle history in Parquet/DuckDB, client WebSocket.

**Tasks.**
1. Models in `market`: `MarketTick` (instrumentId, ts, lastPrice, bid, ask, volume, oi, mode), `Candle` (instrumentId, timeframe, openTime, o/h/l/c, volume, oi, synthetic flag), `Timeframe {M1, M3, M5, M15, H1, D1}`.
2. `TickBus` (in-process, non-durable): `subscribe(instrumentId, listener)`, `publish(tick)`; single-writer, bounded queue, drop-oldest with a metric on overflow.
3. `MarketDataStreamer`: manages the broker ticker connection (`streamMarketData`), subscription set, mode per instrument (`FULL` for watched, `LTP` otherwise), reconnect with exponential backoff, gap detection (no tick for any subscribed instrument for 10 s during session → `marketData: STALE` in readiness). Default watchlist config `hejje.market.watchlist` (NIFTY 50, NIFTY BANK, INDIA VIX, nearest NIFTY and BANKNIFTY futures). `POST /market/subscriptions {instrumentIds}` / `DELETE`.
4. `QuoteCache`: last tick per instrument with staleness; `GET /market/quotes?ids=`; falls back to `getQuote` REST if never streamed.
5. `CandleBuilder`: ticks → 1-minute candles aligned to IST minute boundaries; derive `M3/M5/M15/H1` from 1-minute; minutes with no tick produce a synthetic candle (o=h=l=c=last close, volume 0, `synthetic=true`). Publishes `CandleClosedEvent(candle)` on the `TickBus` (not the durable registry). Writes closed candles to `market_candle` Postgres table; a nightly job prunes rows older than 15 sessions.
6. Historical store:
   - `HistoricalCandleStore` public API: `write(instrument, timeframe, candles)`, `read(instrument, timeframe, from, to)`, `coverage(instrument, timeframe)`.
   - Implementation with DuckDB JDBC: Parquet files at `${hejje.data-dir}/candles/{timeframe}/{instrumentId}/{yyyy}.parquet`; write via a DuckDB temp table + `COPY … TO … (FORMAT PARQUET)`; read via `read_parquet` glob with date filters.
   - `HistoricalBackfillJob`: `POST /market/history/backfill {instrumentId, timeframe, from, to}` → chunks requests (≤60 days for minute data), respects the historical rate bucket (M1.6 limiter; use a simple 3/s throttle until then), stores, reports progress `GET /market/history/jobs/{id}`; `GET /market/history/coverage?instrumentId=`.
   - `GET /market/candles?instrumentId=&timeframe=&from=&to=` reads Postgres for recent, Parquet for older, merges.
7. Tick recording and replay: `hejje.market.record=true` writes ticks to `${data-dir}/ticks/{date}/…parquet`; `ReplayMarketDataSource` (dev profile) replays a recorded day at configurable speed into the `TickBus`.
8. Client WebSocket `/ws/market`: JSON messages `{type: "tick"|"candle", …}` for instruments the client subscribes to (`{"subscribe":[ids]}`), auth per M0.3.

**Acceptance.**
- Golden test: a recorded tick fixture produces the expected 1m and 5m candles (fixture CSV checked in).
- Synthetic candle inserted for an empty minute; 5m candle boundaries at :00/:05… IST.
- Backfill chunking test with WireMock (dates split correctly, no overlap); Parquet write→read round trip; coverage reports gaps.
- Replay of a recorded day yields identical candles to the live run that recorded it.

**Verification.** `./gradlew test --tests 'money.hejje.market.*'`

---

## M1.4 Order intents, order state machine, execution engine  (size: L)

**Goal.** The only path to the broker: intent → validation → (risk stub) → order → broker → state updates from broker events, with idempotency.

**Tasks.**
1. Tables (all rows carry `mode`):
   - `order_intent(id, idempotency_key, client_id, source ActorType, actor_id, strategy_id, signal_id, instrument_id, side, quantity, order_type, product, limit_price, trigger_price, stop_price, target_price, max_risk_paise, reason {MANUAL, STRATEGY_SIGNAL, STRATEGY_EXIT, POSITION_CLOSE, KILL_SWITCH, AGENT_PROPOSAL, WEBHOOK}, mode, status {CREATED, VALIDATING, RISK_REJECTED, READY, SUBMITTED, FAILED}, validation_errors jsonb, created_at)` unique `(client_id, idempotency_key)`.
   - `risk_decision(id, intent_id, outcome, checks jsonb, snapshot jsonb, created_at)` (populated by M1.5).
   - `hejje_order(id, intent_id, mode, broker, broker_order_id, instrument_id, side, quantity, filled_quantity, average_price, order_type, product, limit_price, trigger_price, state, last_broker_status, placed_at, updated_at, raw jsonb, parent_order_id, role {ENTRY, STOP, TARGET, EXIT})`.
   - `order_event(id, order_id, seq, from_state, to_state, source {BROKER_WS, BROKER_POSTBACK, BROKER_POLL, USER, SYSTEM, RECONCILIATION}, payload jsonb, ts)`.
   - `trade(id, order_id, broker_trade_id, instrument_id, side, quantity, price, ts, mode, strategy_id)`.
   - `position(id, mode, instrument_id, product, strategy_id, net_quantity, average_price, realized_pnl_paise, day_buy_qty, day_sell_qty, opened_at, updated_at)` unique `(mode, instrument_id, product, strategy_id)`.
   - `idempotency_record(client_id, key, request_hash, response_status, response_body, created_at)` with 24 h TTL cleanup.
2. `OrderState` enum exactly PRD §36; `OrderStateMachine` with an explicit transition table; illegal transitions throw `IllegalTransition`, are audited, and leave state unchanged.
3. `ExecutionEngine` (module `execution`, public API):
   - `submit(OrderIntentCommand)` → pipeline: idempotency lookup → persist intent → **validation** (instrument active, qty multiple of lot size, price on tick, session open or explicit AMO flag, product allowed, mode matches server mode, `ExecutionReadiness.isExecutionEnabled()`, broker connected) → **risk** (`RiskEngine.evaluate`, stubbed to APPROVE until M1.5 but the call site and persistence exist) → create `hejje_order` in `READY` → rate limiter (M1.6; pass-through now) → `SUBMITTING` → adapter `placeOrder` → `BROKER_ACCEPTED` or `REJECTED`; on `TIMEOUT`/`NETWORK` → `UNKNOWN` and schedule reconciliation (poll `getOrders` for a matching tag within 30 s; the request `tag` carries the Hejje order id).
   - `modify(orderId, ModifyCommand)` → `MODIFY_PENDING` → adapter → wait broker event; `cancel(orderId)` → `CANCEL_PENDING`; `closePosition(instrumentId, product, strategyId?)` → opposite MARKET intent with reason `POSITION_CLOSE`; `cancelAllOpen()`, `closeAllPositions()`.
4. Broker updates: adapter order-update callback (ticker `order` messages) and `POST /broker/postback` (verify checksum; public endpoint, rate limited) → `BrokerOrderUpdate` → state machine (`OPEN`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED`, `REJECTED`); fills create `trade` rows and update `position` (average price, realized P&L FIFO). Poll fallback every 5 s for orders in non-terminal states older than 10 s.
5. Durable events: `OrderIntentCreatedEvent`, `OrderSubmittedEvent`, `OrderStateChangedEvent`, `OrderFilledEvent`, `PositionChangedEvent`.
6. Idempotency: `Idempotency-Key` header mandatory on all transactional endpoints; same key + same body → stored response; same key + different body → 422; in-flight duplicate → 409.
7. Endpoints: `POST /orders/intents` (`orders:execute`), `GET /orders?state=&from=&to=`, `GET /orders/{id}` (with events), `POST /orders/{id}/modify`, `POST /orders/{id}/cancel` (`orders:cancel`), `POST /orders/cancel-all`, `GET /positions`, `POST /positions/close` `{instrumentId, product}` and `POST /positions/close-all` (`positions:close`), `GET /trades?from=&to=`.
8. Client WebSocket `/ws/events`: pushes order, trade, position, broker-status, readiness changes.
9. Audit types used: `ORDER_INTENT_CREATED, RISK_CHECK_PASSED/FAILED, ORDER_SUBMITTED, BROKER_ACCEPTED, ORDER_FILLED, ORDER_CANCELLED, ORDER_REJECTED, POSITION_CLOSED`.

**Acceptance.**
- Transition-table test covers every legal edge and rejects an illegal one.
- Idempotent replay returns the identical body; concurrent duplicate submissions (two threads, same key) create one order.
- Fake adapter `dropAck()` → order goes `UNKNOWN` → poll finds it → `OPEN`.
- Partial fill then fill: `trade` rows and `position` averages correct (FIFO realized P&L test with three fills).
- Validation failures return 422 problem+json with a list of reasons and never call the adapter.

**Verification.** `./gradlew test --tests 'money.hejje.orders.*' --tests 'money.hejje.execution.*'`

---

## M1.5 Risk engine v1, kill switch, position sizing  (size: M)

**Goal.** Deterministic pre-trade controls (PRD §31 subset for MVP, §32 kill switch) that no client can bypass.

**Tasks.**
1. `risk` module: `RiskEngine.evaluate(OrderIntent, AccountSnapshot, RiskLimits) → RiskDecision(outcome, List<RiskCheck{name, passed, observed, limit, message}>)`. Pure function; all inputs assembled by `AccountSnapshotBuilder` (today's realized/unrealized P&L from positions + `QuoteCache`, open position count, trades today, consecutive losses today, margin from `getFunds()`, last trade time per instrument).
2. `risk_limits` table (one row per mode) editable via `PUT /risk/limits` (`risk:write`): `max_loss_per_day, max_realized_loss, max_total_loss_incl_unrealized, max_capital_deployed, max_margin_utilization_pct, max_open_positions, max_gross_exposure, max_trades_per_day, max_risk_per_trade, max_quantity, max_notional, min_reward_risk, mandatory_stop, max_stop_distance_pct, no_new_trades_after (default 14:45), no_averaging_down, no_reentry_minutes (10), max_consecutive_losses (3)`. Seed sensible defaults in a migration.
3. Checks implemented (each a small class): daily loss, realized loss, total loss, open positions, trades per day, risk per trade (`|entry−stop| × qty`), quantity, notional, margin utilization (via adapter `getOrderMargins` when available, else notional), min R:R when target present, mandatory stop (configurable; exits and closes exempt), max stop distance, trading window, averaging down (same instrument, same direction, losing position), re-entry cooldown, consecutive losses, kill switch state, broker/readiness (delegates to `ExecutionReadiness`).
   - **Exposure-reducing intents (reason `POSITION_CLOSE`, `STRATEGY_EXIT`, `KILL_SWITCH`, or side opposite to an open position) skip limit checks except broker/readiness.**
4. Kill switch: table `kill_switch(mode, stop_new_orders bool, set_at, set_by, reason)`. `POST /risk/kill-switch {action: STOP_NEW_ORDERS | CANCEL_ALL_OPEN | CLOSE_ALL_POSITIONS, confirmation}` — `CLOSE_ALL_POSITIONS` requires `confirmation == "CLOSE ALL"`; `CANCEL_ALL`/`CLOSE_ALL` also set `stop_new_orders`. `DELETE /risk/kill-switch` (re-arm) requires `admin`. Auto-trip when daily loss limit is breached (`KILL_SWITCH_ENABLED` audit with reason `DAILY_LOSS`). Local CLI: `java -jar hejje-server.jar --kill` runs the switch directly against the DB and then calls the local API for cancel/close (documented in RUNBOOK).
5. Position sizing: `PositionSizer.size(entry, stop, riskMoney, lotSize, maxQty) → Quantity` (floor to lot); `POST /risk/position-size`.
6. `GET /risk` dashboard (PRD §54) and `GET /risk/limits`.
7. Wire `RiskEngine` into the M1.4 pipeline; persist `risk_decision`; `RISK_REJECTED` intents return 422 with the check list.

**Acceptance.**
- Table-driven tests: one passing and one failing case per check.
- Kill switch `STOP_NEW_ORDERS` rejects a new BUY but allows a `POSITION_CLOSE` intent.
- Daily loss breach auto-trips the switch and audits it.
- Sizing floors to lot size and respects `max_quantity`.

**Verification.** `./gradlew test --tests 'money.hejje.risk.*'`

---

## M1.6 Reconciliation, startup recovery, rate limiting, latency, gating  (size: L)

**Goal.** The daemon is trustworthy after restarts, disconnects, and broker divergence.

**Tasks.**
1. `ReconciliationService` (module `execution`): every 30 s during session, at startup, and on demand (`POST /execution/reconcile`): compare broker orderbook/tradebook/positions with local rows (state, filled qty, avg price, net qty). Differences → `reconciliation_issue(id, kind, severity {INFO, WARN, CRITICAL}, instrument_id, order_id, expected, observed, detected_at, resolved_at)`; local order states corrected from broker truth with `source=RECONCILIATION` events; unknown broker orders (not created by Hejje) imported with `source=EXTERNAL`. `CRITICAL` (position quantity mismatch) → if `hejje.reconciliation.pause-on-critical=true` set kill switch `STOP_NEW_ORDERS`; readiness check `reconciliation` red until resolved (`POST /execution/reconciliation-issues/{id}/resolve`). Audit and `ReconciliationIssueEvent`.
2. `ExecutorBootstrap` implementing PRD §63 in order: executor lease (single-row `executor_lease(owner, acquired_at, expires_at)` with TTL heartbeat every 10 s — one process only, standby support comes in Phase 5), clock check, egress IP, broker session, fetch orders/trades/positions, reconcile, restore in-flight orders (`UNKNOWN`/`*_PENDING` → poll), then enable execution. Each step reported in `ExecutionReadiness` and health.
3. `BrokerRateLimiter` (Bucket4j) with config `hejje.broker.limits`: `orders-per-second (10), orders-per-minute (200), orders-per-day (3000), modify-per-second, quote-per-second (1), historical-per-second (3), general-per-second (10)`; applied inside the adapter call path for every operation; when exhausted, transactional calls fail fast with `RATE_LIMITED` (no queueing of orders), reads queue up to a bounded wait. Metrics: tokens remaining, rejections, queue depth exposed as `orderQueue` in health.
4. Latency: Micrometer timers for PRD §44 hops: `api.request`, `risk.evaluate`, `execution.queue`, `broker.call{op}`, `broker.ack`, `signal.to.ack` (Phase 2), `signal.to.fill` (Phase 2). `GET /server/latency` with p50/p95/p99 per timer. Warning log + event when `broker.ack` p95 > configured threshold.
5. `LiveTradingGate.check(intent)` composing: server mode, kill switch, readiness (lease, clock, IP, broker, reconciliation, market data staleness). Single place the pipeline calls; reasons returned to the client.

**Acceptance.**
- Fake broker seeded with an order/position Hejje does not know → startup imports it, marks issue, execution enabled only after resolve when critical.
- Fake broker reporting +100 while local says +50 → CRITICAL, kill switch set (config on), health shows reason.
- Rate limiter test: 11th order in a second is rejected before the adapter is called.
- Startup with a `SUBMITTING` order and a fake ack → state recovered to `OPEN`.
- Two processes on the same DB: second fails to acquire the lease and stays read-only.

**Verification.** `./gradlew test --tests 'money.hejje.execution.*' --tests 'money.hejje.system.*'`

---

## M1.7 Paper mode and cost model  (size: M)

**Goal.** Paper trading with live data, simulated fills and fees, structurally separate from live.

**Tasks.**
1. `costs` package in `common` (shared later by the backtester): `CostModel.compute(fill) → CostBreakdown{brokerage, stt, exchangeTxn, gst, sebi, stampDuty, total}` from `config/costs.yaml` (versioned). Defaults to fill in and verify against current Zerodha/NSE charge sheets: brokerage ₹20 or 0.03% per executed order (lower); STT intraday equity 0.025% sell, futures 0.02% sell, options 0.1% on premium sell; exchange txn NSE equity ≈0.00297%, futures ≈0.00173%, options ≈0.03503%; GST 18% on brokerage + txn + SEBI; SEBI ₹10/crore; stamp duty buy side: intraday equity 0.003%, futures 0.002%, options 0.003%. Mark values `verify: true` in YAML until confirmed.
2. `broker.paper.PaperBrokerAdapter`: implements transactional operations against `QuoteCache`/`TickBus`: MARKET fills at LTP ± slippage (`hejje.paper.slippage-bps`, default 5), LIMIT fills when LTP crosses, SL/SL-M trigger then fill, optional partial fills (`hejje.paper.partial-fill-probability`), latency jitter; order updates emitted through the same callback path; positions/funds simulated with a configurable starting capital; fees from `CostModel`. Non-transactional calls (quotes, history, instruments, streaming) delegate to the real broker adapter when a session exists, otherwise to fake data.
3. Mode wiring: `hejje.mode=PAPER` selects `PaperBrokerAdapter` for transactional ops. Every API response carries header `X-Hejje-Mode`; `/server/health` shows it. Switching mode requires restart; startup refuses `CONFIRM` if open PAPER positions exist for today (warning only).
4. Paper ledger separation: `mode` column already on all rows; `GET /orders|positions|trades` filter by current mode by default, `?mode=` to inspect the other.
5. `GET /trades/{id}/costs` returns the breakdown; positions carry `fees_paise`.

**Acceptance.**
- End-to-end paper trade in tests: intent → simulated fill → position → close → realized P&L net of fees matches a hand-computed fixture.
- WireMock asserts zero calls to Kite order endpoints in PAPER mode across the whole test suite.
- LIMIT order fills only after a crossing tick; SL triggers correctly for both sides.

**Verification.** `./gradlew test --tests 'money.hejje.broker.paper.*' --tests 'money.hejje.common.costs.*'`

---

## M1.8 Web client v1  (size: L)

**Goal.** The basic Web screens from PRD §72 for Phase 1 (Today/Strategies as placeholders).

**Tasks.**
1. Scaffold `web/`: Vite + React + TS, React Router, TanStack Query/Table, `lightweight-charts`, minimal component set (no heavy UI kit). `.env` with `VITE_API_URL`. Auth: login page, access token in memory, refresh via cookie, redirect on 401. Typed API client generated or hand-written per `docs/api.md`. WS clients for `/ws/events` and `/ws/market` with reconnect.
2. Global chrome: left nav (Today, Pulse, Strategies, Positions, Lab, Hejje AI as disabled placeholders where the phase is not built; Orders, Trades, Risk, Broker, Server, Settings active); **mode banner** across the top: `● LIVE` red when `CONFIRM|AUTO`, `PAPER` blue, unmistakable and persistent; header status dots: server, broker, market data.
3. Screens: `/broker` (status, Connect button opening Kite login, logout), `/orders` (tabs Pending/Open/Filled/Cancelled/Rejected, PRD §51 columns, modify/cancel dialogs, audit trail drawer via `/audit?orderId=`), `/positions` (PRD §52 columns, close, close-all, LTP live from WS), `/trades` (with cost breakdown), `/risk` (dashboard §54, limits editor, kill switch with typed confirmation and three separate buttons), `/system` (health §41, latency, reconciliation issues with resolve, egress IP), `/settings` (API keys management, mode display, watchlist), manual order form (instrument search, side, qty or risk-based sizing, type, price, stop, target; shows the risk decision on rejection).
4. Every transactional call sends a client-generated `Idempotency-Key` (UUID) and disables the button until a response arrives.
5. Tests: Vitest for API client and sizing form logic; Playwright smoke (login → broker status → place paper order → see it in Orders → close position) against the dev server with `FakeBrokerAdapter`. Add `web` job to CI.

**Acceptance.** Smoke test passes in CI; banner visible on every route; no broker secret anywhere in the bundle (grep for `api_secret`).

**Verification.** `cd web && npm ci && npm run lint && npm test && npm run build && npm run e2e`

---

## M1.9 TUI v1  (size: M)

**Goal.** `hejje` terminal client for monitoring and order management over the same API.

**Tasks.**
1. `tui/` Go module `hejje.money/tui`, binary `hejje`. Config `~/.config/hejje/config.yaml` (`server_url`) and API key from `HEJJE_API_KEY` env (never stored in the file). Typed API client package `internal/api` (REST + WS).
2. Cobra commands: `status`, `positions`, `orders`, `order <id>`, `cancel <id>`, `close <instrument>`, `close-all`, `risk`, `broker`, `server`, `logs` (streams `/ws/events`), `kill [--cancel-all|--close-all]` with interactive confirmation (`CLOSE ALL` typed), `order place --instrument --side --qty|--risk --type --price --stop --target` with confirmation. JSON output flag `--json` for scripting. Client-generated idempotency keys.
3. `hejje` with no args → Bubble Tea dashboard per PRD §5.4 sketch: header with mode (`● LIVE` / `PAPER`), index quotes, positions table with live P&L, open orders, footer with daily P&L vs limit and server/broker/market dots; keys: `c` cancel selected order, `x` close selected position, `K` kill switch (confirm), `r` refresh, `q` quit. Best-Hejje card area shows "No strategies deployed" until Phase 2.
4. `go test` for API client (httptest server) and key handling; `go vet`; add `tui` job to CI; `goreleaser`-style build script for linux/mac.

**Acceptance.** All commands work against the dev server with fake broker; dashboard updates from WS; `kill` requires confirmation; `--json` output stable.

**Verification.** `cd tui && go vet ./... && go test ./... && go build ./cmd/hejje`

---

## Phase 1 exit checklist

- [ ] PAPER: place, modify, cancel, fill, close; audit trail complete; fees visible.
- [ ] CONFIRM (live, after static IP registered): one 1-share equity round trip; `hejje_order` ↔ Kite orderbook match; latency recorded.
- [ ] Restart the server mid-session with an open order and an open position: recovered and reconciled without manual intervention.
- [ ] Duplicate request test (same idempotency key twice, retried network call) creates exactly one broker order.
- [ ] Broker logout during session: readiness red, new intents rejected with a clear reason, closes still allowed once reconnected.
- [ ] Egress IP mismatch simulated: execution disabled, health and TUI show it.
- [ ] Kill switch from Web, TUI, API, and local CLI all work.
