# Hejje HTTP API

Base path: `/api/v1`. All responses are JSON. Errors use RFC 7807 `application/problem+json` (from M0.2).

## Server

### `GET /api/v1/server/ping`

Public liveness probe.

```json
{ "status": "UP", "time": "2026-09-08T00:00:00Z" }
```

### `GET /api/v1/server/health`

Scope: `market:read`. Each PRD section 41 line is `{status, detail}`; checks that later milestones fill
report `NOT_CONFIGURED`. `executionEnabled` is false whenever any readiness check is blocking, with `reasons`.

```json
{ "status": "UP", "mode": "PAPER", "version": "0.1.0-SNAPSHOT", "time": "2026-09-08T00:00:00Z",
  "executionEnabled": true, "reasons": [],
  "executionServer": { "status": "HEALTHY", "detail": "mode PAPER" },
  "staticIp":  { "status": "VERIFIED", "detail": "203.0.113.10" },
  "broker":    { "status": "NOT_CONFIGURED", "detail": "arrives in a later milestone" },
  "marketData":{ "status": "NOT_CONFIGURED", "detail": "arrives in a later milestone" },
  "database":  { "status": "HEALTHY", "detail": "SELECT 1 ok" },
  "clockSync": { "status": "HEALTHY", "detail": "drift 120 ms (limit 2000 ms)" },
  "riskEngine":{ "status": "NOT_CONFIGURED", "detail": "arrives in a later milestone" },
  "orderQueue":{ "status": "NOT_CONFIGURED", "detail": "arrives in a later milestone" } }
```

`staticIp` is one of `VERIFIED | MISMATCH | UNKNOWN | SKIPPED`; `clockSync` one of `HEALTHY | DEGRADED | UNKNOWN | SKIPPED`.

### `GET /actuator/health`

Spring Boot actuator liveness/readiness. Public.

## Common behaviour

- Every request may send `X-Correlation-Id` (UUID). The server echoes it (or a generated one) on every response.
- Errors are RFC 7807 problems. Validation errors carry `errors: [{field, message}]`. Every problem carries `correlationId`.

```json
{ "type": "https://hejje.money/problems/validation", "title": "Validation failed", "status": 400,
  "detail": "Validation failed", "instance": "/api/v1/orders", "correlationId": "0192...",
  "errors": [ { "field": "quantity", "message": "must be greater than or equal to 1" } ] }
```

## Audit

### `GET /api/v1/audit?from=&to=&type=&orderId=&page=0&size=50`

Scope: `admin`. `from`/`to` are ISO-8601 instants (`to` exclusive),
`type` is an `AuditEventType`, `size` is at most 500. Newest first.

```json
{ "content": [ { "id": "0192...", "ts": "2026-09-08T04:00:00Z", "type": "ORDER_SUBMITTED", "actorType": "USER",
    "actorId": "admin", "correlationId": "0192...", "orderId": "0192...", "clientSource": "tui",
    "payload": { "symbol": "NSE:INFY", "qty": 10 } } ],
  "page": 0, "size": 50, "total": 1 }
```

## Authentication

Send `Authorization: Bearer <token>` where the token is either a JWT access token (user login) or a client
credential key `hejje_<prefix>_<secret>`. Scopes: `market:read strategies:read strategies:write orders:prepare
orders:execute orders:cancel positions:close risk:read risk:write admin sim:run bot:decide` (`sim:run`: SIM replay
sessions, `bot:decide`: a bot's decisions; Phase 7).
Users hold every scope; clients
hold the scopes they were created with. Missing token: 401. Missing scope: 403. Both are problem+json.

WebSocket handshakes (`/ws/*`) pass the token as `?token=`. API keys are accepted there only with `market:read`.

Rate limits per principal (per IP when anonymous): 20 req/s, burst 40; non-GET calls under
`/api/v1/orders`, `/api/v1/positions`, `/api/v1/risk`, `/api/v1/auth/clients`: 5 req/s, burst 5.
Exceeding returns 429 with `Retry-After` (seconds).

### `POST /api/v1/auth/login`

Public. Optional header `X-Client-Source` (`web`, `tui`, ...) is recorded in the audit event.

```json
{ "username": "admin", "password": "..." }
```
```json
{ "accessToken": "eyJ...", "tokenType": "Bearer", "expiresAt": "2026-09-08T00:15:00Z" }
```
Also sets cookie `hejje_refresh` (HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth, 12 h). Wrong
credentials: 401. Audited as `AUTH_LOGIN` / `AUTH_LOGIN_FAILED`.

### `POST /api/v1/auth/refresh`

Public; needs the `hejje_refresh` cookie. Rotates the refresh token and returns a new access token in the
same shape as login. A reused, expired or revoked cookie: 401.

### `POST /api/v1/auth/logout`

Public; revokes the refresh token in the cookie and clears it. 204.

### `GET /api/v1/auth/me`

Any authenticated caller.

```json
{ "id": "0192...", "name": "admin", "type": "USER", "scopes": ["admin", "market:read", "..."] }
```

### `POST /api/v1/auth/clients`

Scope: `admin`. The `key` is returned once and never stored (only a SHA-256 of the secret is).

```json
{ "name": "research-agent", "scopes": ["market:read", "strategies:read"], "expiresAt": "2027-01-01T00:00:00Z" }
```
```json
{ "id": "0192...", "name": "research-agent", "key": "hejje_ab12cd34_...", "scopes": ["market:read", "strategies:read"], "expiresAt": "2027-01-01T00:00:00Z" }
```
Audited as `CLIENT_CREATED`.

### `GET /api/v1/auth/clients`

Scope: `admin`. Lists `{id, name, keyPrefix, scopes, createdAt, expiresAt, revokedAt, lastUsedAt}`.

### `DELETE /api/v1/auth/clients/{id}`

Scope: `admin`. Revokes the key (204; 404 if unknown). Audited as `CLIENT_REVOKED`.

## Instruments

Symbols follow `docs/symbols.md`. All instrument endpoints need `market:read` unless stated.

### `GET /api/v1/instruments?q=&exchange=&type=&limit=20`

Prefix search on symbol/underlying and substring search on name; active instruments first; `limit` at most 200.

```json
[ { "id": "0192...", "symbol": "NIFTY", "name": "NIFTY", "exchange": "NFO", "type": "FUT", "underlying": "NIFTY",
    "expiry": "2026-09-29", "lotSize": 75, "tickSize": 0.05, "active": true, "updatedAt": "2026-09-08T02:30:00Z",
    "hejjeSymbol": "NFO:NIFTY:FUT:2026-09-29" } ]
```

### `GET /api/v1/instruments/{id}`

One instrument (shape above); 404 when unknown.

### `GET /api/v1/instruments/resolve?symbol=NFO:NIFTY:FUT:2026-09-29`

Resolves a canonical symbol (or `EXCHANGE:TRADINGSYMBOL`). 400 when the symbol is malformed, 404 when unknown.

### `POST /api/v1/instruments/sync`

Scope: `admin`. Runs the instrument master sync now.

### `POST /api/v1/instruments/export` (Phase 7, M7.2)

Scope: `admin`. Writes every instrument with its id to `<data-dir>/instruments/master.json` and returns
`{ "instruments": 76012, "file": "/data/instruments/master.json" }`. A SIM instance imports it at startup (the Parquet
history is keyed by instrument id); see `docs/simulation.md`.

```json
{ "broker": "fake", "received": 32, "upserted": 32, "deactivated": 0, "activeAfter": 32, "syncedAt": "2026-09-08T02:30:00Z" }
```

## Broker

### `GET /api/v1/broker/login-url`

Scope: `admin`. `{ "broker": "zerodha", "loginUrl": "https://kite.trade/connect/login?api_key=...&v=3" }`

### `GET /api/v1/broker/callback?request_token=&status=`

Public. Target of the broker's login redirect. Exchanges the token and answers `302` to `<hejje.broker.web-url>/broker?connected=1`,
or `/broker?error=auth|login_cancelled|...` on failure. Audited as `BROKER_CONNECTED` / `BROKER_LOGIN_FAILED`.

### `POST /api/v1/broker/login?request_token=`

Scope: `admin`. Same exchange as the callback, returns the status below.

### `GET /api/v1/broker/status`

Scope: `market:read`. PRD section 40 shape.

```json
{ "broker": "zerodha", "state": "CONNECTED", "brokerUserId": "AB1234", "establishedAt": "2026-09-08T02:12:18Z",
  "expiresAt": "2026-09-09T00:30:00Z", "lastCheckedAt": "2026-09-08T04:00:00Z", "detail": "session established",
  "liveTradingEnabled": true }
```
`state` is `CONNECTED | EXPIRED | DISCONNECTED | ERROR`; `liveTradingEnabled` is false unless `CONNECTED`.

### `POST /api/v1/broker/validate`

Scope: `admin`. Calls the broker profile endpoint now; returns the status. An AUTH failure marks the session `DISCONNECTED`.

### `POST /api/v1/broker/logout`

Scope: `admin`. Invalidates the session at the broker, clears the stored token. Audited `BROKER_LOGGED_OUT`.

### `POST /api/v1/broker/postback`

Public (per-IP rate limit). Kite order postback JSON; `checksum` verified with the api secret. `204` accepted, `403` bad
checksum, `400` not JSON. Present only when `hejje.broker.zerodha.api-secret` is configured.

## Market data

All endpoints need `market:read` (backfill and jobs need `admin`). Candle timeframes: `M1 M3 M5 M15 H1 D1`.

### `GET /api/v1/market/quotes?ids=<uuid>,<uuid>`

Last tick per instrument with staleness; falls back to a REST quote for instruments never streamed.

```json
{ "0192...": { "instrumentId": "0192...", "ts": "2026-09-08T04:00:00Z", "lastPrice": 1498.20, "bid": 1498.10,
    "ask": 1498.30, "volume": 123456, "oi": 0, "stale": false } }
```

### `GET /api/v1/market/candles?instrumentId=&timeframe=&from=&to=`

Merges recent candles (Postgres) with older history (Parquet). Ordered by openTime; `from`/`to` are ISO-8601 instants.

```json
[ { "instrumentId": "0192...", "timeframe": "M5", "openTime": "2026-09-08T03:45:00Z", "open": 24950.00,
    "high": 24990.00, "low": 24940.00, "close": 24985.00, "volume": 12345, "oi": 1000500, "synthetic": false } ]
```

### `POST /api/v1/market/subscriptions` · `DELETE /api/v1/market/subscriptions`

Body `{ "instrumentIds": ["<uuid>"] }`. Adds/removes streaming subscriptions (LTP mode; the default watchlist stays FULL
and cannot be removed). Returns `{ "subscribed": ["<uuid>"] }`.

### `POST /api/v1/market/history/backfill`

Scope: `admin`. Body `{ "instrumentId", "timeframe", "from", "to" }`. Returns `{ "jobId": "<uuid>" }`. Minute data is
chunked into <=60-day broker requests; a 3/s throttle stands in until the M1.6 limiter.

### `GET /api/v1/market/history/jobs/{id}`

Scope: `admin`. `{ "jobId", "instrumentId", "timeframe", "from", "to", "status": "RUNNING|DONE|FAILED", "chunksTotal",
"chunksDone", "candlesWritten", "error" }`.

### `GET /api/v1/market/history/coverage?instrumentId=&timeframe=M1`

`{ "instrumentId", "timeframe", "from", "to", "candleCount" }`; a zero `candleCount` means no history stored.

### WebSocket `/ws/market`

Auth by `?token=` (JWT or an API key with `market:read`). Client sends `{"subscribe":["<uuid>"]}` /
`{"unsubscribe":["<uuid>"]}`. Server pushes `{"type":"tick",...}` and `{"type":"candle","candle":{...}}` for subscribed
instruments.

## Orders, positions, trades

Every transactional endpoint requires an `Idempotency-Key` header (a UUID). A replay with the same key and body returns
the original result; the same key with a different body is 422; an in-flight duplicate is 409. Missing header is 400.

### `POST /api/v1/orders/intents`

Scope: `orders:execute`. Submits an order intent (PRD section 30). Runs validation, then risk, then places the order.

```json
{ "instrumentId": "0192...", "side": "BUY", "quantity": 10, "orderType": "LIMIT", "product": "MIS",
  "limitPrice": "1498.50", "triggerPrice": null, "stopPrice": null, "targetPrice": null, "maxRiskPaise": 150000,
  "reason": "MANUAL", "strategyId": null, "signalId": null }
```
201 returns the created order. Validation failure: 422 `problem+json` with a `reasons` array (the adapter is never
called). Risk rejection: 422 with a `checks` array. The order state follows PRD section 36.

### `GET /api/v1/orders?state=&from=&to=&mode=`

Scope: `market:read`. Orders in the current mode (or `?mode=` to inspect the other), newest first.

### `GET /api/v1/orders/{id}`

Scope: `market:read`. `{ "order": {...}, "events": [ { "seq", "fromState", "toState", "source", "ts" } ] }`.

### `POST /api/v1/orders/{id}/modify`

Scope: `orders:execute`. Body `{ "quantity", "orderType", "limitPrice", "triggerPrice" }` (nulls unchanged).

### `POST /api/v1/orders/{id}/cancel` · `POST /api/v1/orders/cancel-all`

Scope: `orders:cancel`. Cancel one order, or every open order in the current mode (`{ "cancelled": n }`).

### `GET /api/v1/positions?mode=` · `POST /api/v1/positions/close` · `POST /api/v1/positions/close-all`

Scope: `market:read` (read) / `positions:close` (close). Close body `{ "instrumentId", "product", "strategyId" }`
submits an opposite MARKET order with reason `POSITION_CLOSE`. Positions are keyed by (mode, instrument, product,
strategy) with average price and realized P&L in paise.

### `GET /api/v1/trades?mode=&from=&to=`

Scope: `market:read`. Fills recorded from the broker, newest first.

### WebSocket `/ws/events`

Auth by `?token=`. Pushes `{"type":"order"|"fill"|"position"|"broker"|"readiness", ...}` as state changes.

## Risk

### `GET /api/v1/risk`

Scope: `risk:read`. Account risk dashboard (PRD section 54): realized/unrealized/net P&L, daily loss limit, gross exposure
and its limit, open positions, trades today, consecutive losses, margin used %, and the kill switch state.

### `GET /api/v1/risk/limits` · `PUT /api/v1/risk/limits`

Scope: `risk:read` / `risk:write`. Limits for the current mode (money fields in paise). The PUT body carries every limit
as a flat field (e.g. `maxLossPerDayPaise`, `maxRiskPerTradePaise`, `maxOpenPositions`, `noNewTradesAfter` as `HH:mm`,
`mandatoryStop`, `noAveragingDown`, `noReentryMinutes`, `maxConsecutiveLosses`).

### `POST /api/v1/risk/kill-switch`

Scope: `risk:write`. Body `{ "action": "STOP_NEW_ORDERS" | "CANCEL_ALL_OPEN" | "CLOSE_ALL_POSITIONS", "confirmation": "CLOSE ALL" }`.
Every action stops new orders. `CLOSE_ALL_POSITIONS` requires `confirmation == "CLOSE ALL"` (else 400); `CANCEL_ALL_OPEN`
and `CLOSE_ALL_POSITIONS` are carried out asynchronously by the execution module. Returns the kill switch state.

### `GET /api/v1/risk/kill-switch` · `DELETE /api/v1/risk/kill-switch`

Scope: `risk:read` (read) / `admin` (re-arm). DELETE clears `stopNewOrders`.

### `POST /api/v1/risk/position-size`

Scope: `risk:read`. Body `{ "entry": "24980.00", "stop": "24935.00", "riskPaise": 200000, "lotSize": 75, "maxQuantity": 0 }`
returns `{ "quantity": n }` floored to whole lots and capped at `maxQuantity` (0 = no cap).

### `GET /api/v1/risk/stop-suggestion?instrumentId=&side=&entry=`

Scope: `risk:read`. Suggests an initial stop for a new position: 1.5 × ATR(14) over the last ten days of M5 bars from
`entry` (default: the last traded price; 400 when neither is known), 1% of the entry when no bars are stored, never
further than the mode's `maxStopDistancePct`, rounded to the tick towards the entry. The manual order form prefills it.

```json
{ "entry": "1500.00", "stop": "1494.00", "basis": "ATR", "atr": 4.0, "distancePct": 0.40, "maxDistancePct": 5.00 }
```
`basis` is `ATR`, `PERCENT` (no bars, `atr` null) or `MAX_DISTANCE` (clamped to the limit).

## Execution operations (reconciliation, latency)

### `POST /api/v1/execution/reconcile`

Scope: `admin`. Runs a reconciliation pass now (PRD section 38) and returns the open issues.

### `GET /api/v1/execution/reconciliation-issues`

Scope: `market:read`. Open issues: `{ id, kind, severity: INFO|WARN|CRITICAL, instrumentId, orderId, expected, observed, detail, detectedAt }`.

### `POST /api/v1/execution/reconciliation-issues/{id}/resolve`

Scope: `admin`. Marks an issue resolved. A CRITICAL issue (position quantity mismatch) blocks execution until resolved.

### `GET /api/v1/server/latency`

Scope: `market:read`. p50/p95/p99 (ms) per PRD section 44 timer: `api.request`/`http.server.requests`, `risk.evaluate`,
`broker.call{op}`, `broker.ack`.

## Paper mode and costs

Every response carries `X-Hejje-Mode` (PAPER / CONFIRM / AUTO). Orders, positions and trades are filtered by the current
mode by default; pass `?mode=` to inspect the other ledger. Positions carry `fees` (paise) and `netRealizedPnl`.

### `GET /api/v1/trades/{id}/costs`

Scope: `market:read`. The itemized transaction cost of a fill (PRD section 12.2), computed by the cost model:
`{ brokerage, stt, exchangeTxn, gst, sebi, stampDuty, total }` (each in paise).

## Strategies (Phase 2, M2.1)

Definitions are YAML (see `docs/strategy-dsl.md`, schema `docs/strategy-schema.json`). Invalid definitions return a
400 problem of type `https://hejje.money/problems/strategy-validation` with `errors: [{path, message}]`. Illegal
lifecycle moves, duplicate names and identical versions return 409.

### `GET /api/v1/strategies`

Scope: `strategies:read`. `[ { id, slug, family, name, createdAt, retiredAt, latestVersion, latestVersionId, latestStatus } ]`.

### `POST /api/v1/strategies`

Scope: `strategies:write`. Body `{ "yaml": "...", "changeNote": "optional" }`. Creates the strategy (slug = `name`)
with version 1 in `DRAFT` and returns the version:

```json
{ "id": "0192...", "strategyId": "0192...", "version": 1, "definitionYaml": "name: nifty_orb...", 
  "definition": { "name": "nifty_orb", "family": "INDEX", "timeframe": "M5", "direction": "LONG",
                  "entry": { "mode": "ALL", "conditions": ["close > opening_range_high(15m)", "close > vwap"] }, "...": "..." },
  "definitionHash": "3f2a...", "changeNote": "initial", "parentVersionId": null, "createdBy": "admin",
  "createdAt": "2026-09-09T04:00:00Z", "status": "DRAFT" }
```

### `POST /api/v1/strategies/validate`

Scope: `strategies:read`. Body `{ "yaml": "..." }` → `{ valid, errors: [{path, message}], definition }`.

### `GET /api/v1/strategies/{id}` · `GET /api/v1/strategies/{id}/versions` · `GET /api/v1/strategies/{id}/versions/{v}`

Scope: `strategies:read`.

### `POST /api/v1/strategies/{id}/versions`

Scope: `strategies:write`. Body `{ "yaml": "...", "changeNote": "Added volume filter" }` (change note required; the
`name` must equal the strategy slug; a definition identical to the latest version is refused with 409).

### `POST /api/v1/strategies/{id}/clone`

Scope: `strategies:write`. Body `{ "name": "nifty_orb_v2_experiment" }`. Copies the latest version into a new strategy.

### `POST /api/v1/strategies/{id}/versions/{v}/status`

Scope: `strategies:write`. Body `{ "status": "BACKTESTED|VALIDATED|PAPER|LIVE|PAUSED|RETIRED", "note": "optional" }`.
Transitions are enforced (`docs/strategy-dsl.md`, "Versions and lifecycle"); `DRAFT → LIVE` is a 409.

### `POST /api/v1/strategies/{id}/versions/{v}/deployments`

Scope: `strategies:write`. Body `{ "mode": "PAPER", "instruments": ["NSE:INFY"], "autonomyLevel": 0, "params": { "risk_rupees": 2000 } }`.
`instruments` defaults to the resolved universe; `mode` defaults to `PAPER`. Returns
`{ id, versionId, strategyId, mode, instrumentIds, autonomyLevel, enabled, params, createdAt, pausedAt, pauseReason }`.

### `GET /api/v1/deployments?versionId=&mode=&enabled=` · `GET /api/v1/deployments/{id}`

Scope: `strategies:read`.

### `PUT /api/v1/deployments/{id}`

Scope: `strategies:write`. Body `{ "enabled": false, "reason": "lunch" }`. Enabling requires the version to be in
`PAPER` or `LIVE`.

## Backtests (Phase 2, M2.3)

See `docs/backtesting.md` for the replay rules, metrics, splits and warnings. Money in request bodies is rupees;
money in responses is `{ "paise": n }`.

### `POST /api/v1/backtests`

Scope: `strategies:write`. Returns 202 with the queued backtest.

```json
{ "strategyId": "0192...", "version": 1, "instruments": ["NFO:NIFTY:FUT:2026-09-24"], "timeframe": "M5",
  "from": "2024-01-01", "to": "2026-08-31", "fillModel": "NEXT_OPEN", "slippageBps": 5,
  "splits": { "type": "FIXED", "inSamplePct": 60, "validationPct": 20, "outOfSamplePct": 20 },
  "initialCapitalRupees": 1000000, "riskPerTradeRupees": 2000 }
```

`versionId` may replace `strategyId` + `version`; `splits` may be `{ "type": "NONE" }` or
`{ "type": "WALK_FORWARD", "trainMonths": 6, "testMonths": 2, "anchored": false }`. 422 when the version does not
exist, the universe resolves to nothing or there are no candles.

### `GET /api/v1/backtests/{id}`

Scope: `strategies:read`.

```json
{ "id": "0192...", "versionId": "0192...", "spec": { "...": "..." }, "status": "DONE", "progressPct": 100,
  "createdAt": "...", "startedAt": "...", "finishedAt": "...",
  "metrics": { "totalTrades": 212, "winningTrades": 98, "winRate": 0.46, "expectancyR": 0.31, "profitFactor": 1.52,
               "maxDrawdown": { "paise": 1840000 }, "sharpe": 1.4, "grossPnl": { "paise": ... }, "totalCosts": { "paise": ... },
               "netPnl": { "paise": ... }, "rDistribution": { "< -2R": 1, "-2R..-1R": 20, "...": 0 },
               "monthly": { "2026-08": { "trades": 12, "netPnl": { "paise": ... }, "winRate": 0.5 } },
               "equityCurve": [ { "time": "...", "value": { "paise": ... } } ], "...": "..." },
  "bySplit": { "IN_SAMPLE": { "...": "..." }, "OUT_OF_SAMPLE": { "...": "..." } },
  "windows": [ { "index": 0, "trainFrom": "...", "testFrom": "...", "testTo": "...", "trades": 14, "expectancyR": 0.2 } ],
  "warnings": [ { "code": "LOW_SAMPLE", "severity": "WARN", "message": "...", "evidence": { "trades": 82 } } ],
  "sessionsExpected": 660, "sessionsWithData": 658, "skippedSignals": 3, "resultHash": "3f...", "engine": "JAVA", "createdBy": "admin" }
```

### `GET /api/v1/backtests/{id}/trades?split=IN_SAMPLE|VALIDATION|OUT_OF_SAMPLE`

Scope: `strategies:read`. `[ { id, instrumentId, split, entryTime, exitTime, side, qty, entryPrice, exitPrice, stop,
target, grossPnl, costs, netPnl, rMultiple, exitReason, evidence: [ { condition, status, lhs, rhs } ] } ]`.

### `GET /api/v1/backtests?versionId=`

Scope: `strategies:read`. Newest first (the last 100 when no version is given).

### `DELETE /api/v1/backtests/{id}`

Scope: `strategies:write`. Cancels a queued/running backtest (status `CANCELLED`) or deletes a finished one.

## Historical dataset (Phase 2, M2.4)

### `POST /api/v1/market/history/continuous`

Scope: `admin`. Body `{ "underlying": "NIFTY", "timeframe": "M5" }`. Stitches every known contract's candles into
the continuous series `NFO:<UNDERLYING>:FUT:CONT` (docs/data.md) and returns it:

```json
{ "id": "6f1c...", "underlying": "NIFTY", "exchange": "NFO", "symbol": "NFO:NIFTY:FUT:CONT", "lotSize": 75, "tickSize": 0.05,
  "segments": [ { "instrumentId": "0192...", "contract": "NFO:NIFTY:FUT:2026-09-29", "expiry": "2026-09-29",
                  "from": "2026-08-27", "to": "2026-09-28", "candles": 1650 } ], "builtAt": "..." }
```

The series id is stable across rebuilds. Backtests use the series automatically for `nearest_future` universe
entries (and aliases such as `NIFTY`) when one exists, and accept its symbol in `instruments`.

### `GET /api/v1/market/history/continuous?underlying=`

Scope: `market:read`. Lists built series (all, or the one for an underlying).

### `GET /api/v1/market/history/integrity?instrumentId=&timeframe=M5&from=2024-01-01&to=2026-08-31`

Scope: `market:read`. Store contents versus the holiday calendar:

```json
{ "instrumentId": "...", "timeframe": "M5", "from": "2024-01-01", "to": "2026-08-31", "sessionsExpected": 660,
  "sessionsWithData": 658, "missingSessions": ["2025-03-14", "2026-01-26"], "expectedBarsPerSession": 75,
  "shortSessions": [ { "session": "2024-05-20", "bars": 40 } ], "totalCandles": 49350, "syntheticCandles": 120,
  "missingSessionPct": 0.3 }
```

## Hejje Score and comparison (Phase 2, M2.5)

Formula: `docs/hejje-score.md`.

### `GET /api/v1/strategies/{id}/score?version=&instrumentId=`

Scope: `strategies:read`. The latest breakdown for one instrument (default: the best-scoring one) of the latest (or
given) version, plus every instrument's latest final score.

```json
{ "strategyId": "...", "versionId": "...", "version": 3,
  "breakdown": { "id": "...", "computedAt": "...", "baseBacktestId": "...", "base": 62.4,
    "cap": null,
    "components": [ { "name": "Expectancy (R)", "weight": 0.25, "score": 70.0, "contribution": 17.5,
                      "evidence": { "out_of_sample": 0.25, "validation": 0.5, "in_sample": 0.5 } }, "..." ],
    "adjustments": [ { "name": "Technical compatibility", "delta": 8, "min": -10, "max": 8,
                       "evidence": [ "3 of 3 entry conditions pass on the last closed bar (...)", "close > vwap: passed (24931.20 vs 24890.05)" ] },
                     { "name": "Recent paper/live performance", "delta": 0, "min": -5, "max": 5, "evidence": [ "no paper/live round trips ..." ] } ],
    "finalScore": 70 },
  "instruments": [ { "instrumentId": "...", "finalScore": 70, "computedAt": "..." } ] }
```

### `POST /api/v1/strategies/{id}/score/recompute?version=`

Scope: `strategies:write`. Recomputes on every deployed instrument (or the resolved universe) and returns the breakdowns.

### `GET /api/v1/strategies/compare?versionIds=a,b,c`

Scope: `strategies:read`. PRD 21 rows: `{ versionId, strategyId, slug, version, status, backtestId, trades, winRate,
profitFactor, expectancyR, maxDrawdownR, similarRegimePerformance, hejjeScore }`.

### `GET /api/v1/strategies/{id}/versions/compare?a=2&b=3`

Scope: `strategies:read`. `{ a: row, b: row, deltas: [ { metric, a, b, changePct, better } ], verdict }`, for example
`"v3 improved: increased expectancy (r) by 68.0%, but reduced trades by 8.4%."`.

## Signals (Phase 2, M2.6)

See `docs/signals.md`. Signals belong to the server's execution mode.

### `GET /api/v1/signals?status=&from=&limit=` · `GET /api/v1/signals/active` · `GET /api/v1/signals/{id}`

Scope: `strategies:read`.

```json
{ "id": "...", "versionId": "...", "strategyId": "...", "deploymentId": "...", "instrumentId": "...", "mode": "PAPER", "side": "BUY",
  "referencePrice": 1507.00, "stop": 1495.00, "target": 1531.00, "riskPerUnit": 12.00,
  "barTime": "2026-09-08T04:05:00Z", "validUntil": "2026-09-08T04:10:00Z",
  "evidence": [ { "condition": "close > opening_range_high(15m)", "status": "PASSED", "lhs": 1507.0, "rhs": 1505.0 } ],
  "status": "ACTIVE", "createdAt": "...", "updatedAt": "..." }
```

### `POST /api/v1/signals/{id}/prepare`

Scope: `orders:prepare`. Sizes and dry-runs risk; nothing is submitted. 409 when the signal is no longer actionable.

```json
{ "signal": { "...": "...", "status": "PREPARED" },
  "proposal": { "instrumentId": "...", "side": "BUY", "quantity": 160, "orderType": "MARKET", "product": "MIS", "stopPrice": "1495.00",
                "targetPrice": "1531.00", "maxRisk": { "paise": 200000 }, "reason": "STRATEGY_SIGNAL", "strategyId": "...", "signalId": "..." },
  "risk": { "outcome": "APPROVED", "checks": [ { "name": "riskPerTrade", "passed": true, "message": "2000.00" } ] },
  "sizing": { "riskRupees": "2000.00", "entryReference": "1507.50", "riskPerUnit": "12.50", "lotSize": 1, "maxQuantity": 0, "quantity": 160 },
  "notes": [] }
```

### `POST /api/v1/signals/{id}/execute`

Scope: `orders:execute`; header `Idempotency-Key` required. Submits the prepared order and returns the `HejjeOrder`
(201). The signal becomes `EXECUTED`; a `strategy_position` tracks the entry, the protective stop and the exit.

### `POST /api/v1/signals/{id}/skip`

Scope: `orders:prepare`. Body `{ "reason": "..." }`. Marks the signal `SKIPPED`.

### `GET /api/v1/signals/positions?live=true`

Scope: `strategies:read`. Strategy-managed positions: `{ id, signalId, deploymentId, versionId, strategyId, instrumentId, mode,
side, quantity, entryPrice, initialStop, stop, target, entryOrderId, stopOrderId, exitOrderId, status: PENDING_ENTRY|OPEN|EXITING|CLOSED,
closeReason, exitPrice, openedAt, closedAt }`.

## Today, analytics and reviews (Phase 2, M2.7)

See `docs/analytics.md`.

### `GET /api/v1/today`

Scope: `strategies:read`.

```json
{ "header": { "indexQuotes": { "INDEX:NIFTY 50": { "lastPrice": 24930.5, "ts": "...", "stale": false } }, "vix": 14.1,
              "regime": null, "breadth": null, "eventRisk": null, "mode": "PAPER", "time": "..." },
  "best": { "versionId": "...", "strategyId": "...", "strategy": "nifty_orb", "version": 1, "deploymentId": "...", "instrumentId": "...",
            "instrument": "NFO:NIFTY:FUT:2026-09-29", "score": 87, "decision": "TRADE", "direction": "BUY", "signalId": "...",
            "signalStatus": "ACTIVE", "signalValidUntil": "...", "entry": 24930, "stop": 24885, "target": 25020, "quantity": 75,
            "riskRupees": 2000, "expectedRewardRupees": 4000, "regime": null, "newsBias": null, "eventRisk": "UNKNOWN",
            "hardBlocks": [], "supportingEvidence": [ "✓ close > opening_range_high(15m) (24930.00 vs 24905.00)", "✓ Reward:risk 2 at the current price" ],
            "risks": [], "backtest": { "trades": 212, "expectancyR": 0.31, "winRatePct": 46, "profitFactor": 1.52, "maxDrawdownR": 9.2 },
            "scoreBreakdown": { "base": 78, "adjustments": [ { "name": "Technical compatibility", "delta": 8 } ], "final": 87 } },
  "ranked": [ "..." ], "noTrade": null }
```

`noTrade` carries the message when `best` is null. `GET /api/v1/today/history?signalId=` lists the recorded decisions for a signal.

### `GET /api/v1/analytics/pnl?groupBy=strategy&from=2026-09-01&to=2026-09-09&mode=PAPER`

Scope: `market:read`. `groupBy`: `strategy | version | instrument | weekday | hour | regime`.

```json
{ "groupBy": "strategy", "mode": "PAPER", "from": "...", "to": "...",
  "buckets": [ { "key": "0192...", "label": "nifty_orb", "trades": 12, "wins": 7, "grossPnl": { "paise": 1850000 }, "fees": { "paise": 42000 },
                 "netPnl": { "paise": 1808000 }, "winRate": 0.58, "averageR": 0.42 },
               { "key": "MANUAL", "label": "MANUAL", "trades": 3, "...": "..." } ],
  "summary": { "roundTrips": 15, "grossPnl": { "paise": ... }, "fees": { "paise": ... }, "netPnl": { "paise": ... } } }
```

### `GET /api/v1/reviews?limit=&mode=` · `GET /api/v1/reviews/{id}` · `GET /api/v1/reviews/by-order/{entryOrderId}`

Scope: `market:read`.

```json
{ "id": "...", "positionId": "...", "strategyPositionId": "...", "strategyId": "...", "strategyVersionId": "...", "signalId": "...",
  "instrumentId": "...", "entryOrderId": "...", "side": "BUY", "quantity": 160, "entryPrice": 1507.50, "exitPrice": 1494.00,
  "openedAt": "...", "closedAt": "...", "grossPnl": { "paise": -216000 }, "fees": { "paise": 9870 }, "netPnl": { "paise": -225870 },
  "outcomeR": -1.13, "expectedSetupValid": true, "entrySlippageBps": 3.32, "exitSlippageBps": 6.69, "ruleAdherencePct": 100,
  "closeReason": "STOP", "context": { "regime": "UNKNOWN", "breadth": "UNKNOWN", "news": "UNKNOWN", "event": "UNKNOWN" }, "notes": "strategy trade" }
```

`POST /api/v1/reviews/positions/{positionId}` (scope `admin`) re-runs the review for a flattened position.

### Development seeding (dev/test profiles)

- `POST /api/v1/market/dev/candles` (scope `admin`, 404 unless `hejje.market.dev-candles=true`):
  `{ "instrumentId": "...", "timeframe": "M5", "store": true, "publish": true, "quote": true,
     "candles": [ { "openTime": "2026-09-10T09:15:00+05:30", "open": 1500, "high": 1505, "low": 1495, "close": 1500, "volume": 50000 } ] }`.
- `POST /api/v1/broker/dev/quote` (scope `admin`, 404 unless the fake broker is active): `{ "instrumentId": "...", "price": 1507.5 }`.
- `POST /api/v1/strategies/{id}/versions/{v}/status` accepts `"force": true` when `hejje.strategy.allow-forced-status=true`.

## Context: market regime (Phase 3, M3.1)

See `docs/regime.md` for the rules. Scope: `market:read` unless noted.

### `GET /api/v1/context/regime`

The current session's snapshot (cached for `hejje.regime.intraday-snapshot`).

```json
{ "date": "2026-09-08", "asOf": "2026-09-08T10:01:00Z", "trend": "STRONG_UP", "volatility": "LOW", "opening": "GAP_CONTINUATION",
  "breadth": "STRONG_POSITIVE", "intradayStructure": "TREND_DAY", "eventEnvironment": "NORMAL",
  "features": { "close": 29480.2, "emaFast": 29210.5, "emaSlow": 28840.1, "emaSlopePct": 1.52, "adx": 41.3, "vix": 13.8, "vixPercentile": 22.0,
                "volatilityPercentile": 24.5, "gapPct": 0.5, "openingRangeClose": 29360.0, "advances": 41, "declines": 7, "breadthRatio": 0.83,
                "intradayBars": 9, "rangeExpansion": 3.4, "closePosition": 0.97, "rangeAtr": 1.1, "vwapCrosses": 0 },
  "evidence": [ "Trend STRONG_UP: close 29480.20 vs EMA20 29210.50 / EMA50 28840.10 (close above rising EMAs), EMA20 slope +1.52% over 5 sessions, ADX 41.3",
                "Volatility LOW: VIX 13.80 at the 22nd percentile, ATR14/close at the 27th percentile (combined 24 over 250 sessions)",
                "Opening GAP_CONTINUATION: open 29360.00 vs previous close 29214.00 (gap +0.50%), 15-minute close 29390.00",
                "Breadth STRONG_POSITIVE: 41 advances / 7 declines, 40 of 48 above VWAP (83% positive, 48 of 50 constituents)",
                "Intraday structure TREND_DAY (progressive, 9 bars): range 3.4x the opening range, close at 97% of the day range, 0 VWAP crosses",
                "Event environment NORMAL" ],
  "classifierVersion": "1", "finalLabel": false }
```

Every dimension is `UNKNOWN` (with the reason in `evidence`) when its inputs are missing or the engine is disabled.

### `GET /api/v1/context/regime/history?from=2026-06-01&to=2026-09-08`

Stored final labels (same shape, `finalLabel: true`) for the sessions in the range under the current classifier version.

### `GET /api/v1/context/regime/intraday?date=2026-09-08`

Stored intraday snapshots of one session, oldest first.

### `POST /api/v1/context/regime/label?from=2023-09-01&to=2026-09-08`

Scope: `admin`. Labels every session in the range with index data and upserts the final rows.

```json
{ "from": "2023-09-01", "to": "2026-09-08", "classifierVersion": "1", "sessions": 742, "labelled": 740, "hash": "9f3c…" }
```

### Regime-conditional backtest statistics

`GET /api/v1/backtests/{id}` now carries `byRegime`, `similarRegime` and `similarRegimeNote` for DONE runs;
`GET /api/v1/backtests/{id}/regimes?dims=trend,volatility` (scope `strategies:read`) returns the block alone along
any of `trend, volatility, opening, breadth, structure, event`.

```json
{ "dims": ["trend", "volatility"],
  "byRegime": [ { "key": "RANGE × NORMAL", "trades": 88, "winRate": 0.39, "expectancyR": 0.12, "profitFactor": 1.21, "netPnl": { "paise": 812000 } },
                { "key": "UP × LOW", "trades": 61, "winRate": 0.52, "expectancyR": 0.48, "profitFactor": 1.9, "netPnl": { "paise": 2410000 } } ],
  "similarRegime": { "current": "UP × LOW", "trades": 61, "winRate": 0.52, "expectancyR": 0.48, "profitFactor": 1.9, "netPnl": { "paise": 2410000 },
                     "overallTrades": 212, "overallExpectancyR": 0.31 },
  "note": null }
```

`similarRegime` is null with `note` explaining why (current regime unknown along the dimensions, or no trades in it).

## Context: Pulse (Phase 3, M3.2)

See `docs/pulse.md`. Scope: `market:read`.

### `GET /api/v1/context/pulse`

```json
{ "date": "2026-09-08", "asOf": "2026-09-08T05:16:00Z",
  "technical": { "direction": "BULLISH", "strength": "STRONG", "score": 72, "coverage": 0.9,
                 "components": [ { "name": "index_trend", "weight": 20, "value": 1.0, "contribution": 20, "evidence": "Index trend STRONG_UP" },
                                 { "name": "relative_volume", "weight": 5, "value": null, "contribution": 0, "evidence": "Futures volume history unavailable" } ],
                 "evidence": [ "+20.0 Index trend STRONG_UP", "+15.0 Index 24930.00 is +0.35% from its session average 24843.00", "n/a  Futures volume history unavailable" ] },
  "market": { "regime": "Trending ↑", "volatility": "Moderate", "breadth": "Positive",
              "sectors": [ { "name": "Banking", "symbol": "INDEX:NIFTY BANK", "label": "STRONG", "changePct": 1.8, "relativePct": 1.1 },
                           { "name": "IT", "symbol": "INDEX:NIFTY IT", "label": "UNKNOWN" } ],
              "globalContext": "NEUTRAL" } }
```

### `GET /api/v1/context/pulse/history?date=2026-09-08`

The stored snapshots of a session (one per interval while the session was open), oldest first.

The Today header (`GET /api/v1/today`) now carries `regime` (the trend label), `regimeLabels` (all six dimensions) and
`breadth` from the regime snapshot; `UNKNOWN` when the engine has no data.

## Events and event risk (Phase 3, M3.3)

See `docs/events.md`.

### `GET /api/v1/events?from=2026-10-01&to=2026-10-31&instrumentId=&all=`

Scope: `market:read`. Market events plus the instrument's (and, for a derivative, its underlying's); `all=true` (the
default when no `from` and no instrument are given: the next 7 days) returns every event.

```json
[ { "id": "...", "type": "RBI_POLICY", "scope": "MARKET", "symbol": null, "title": "RBI MPC decision", "startsAt": "2026-10-07T04:30:00Z", "endsAt": null,
    "allDay": false, "source": "curated", "externalKey": "RBI_POLICY|MARKET||2026-10-07|rbi mpc decision", "confidence": 0.95, "raw": { "...": "..." }, "importedAt": "..." },
  { "id": "...", "type": "RESULTS", "scope": "INSTRUMENT", "instrumentId": "...", "symbol": "NSE:INFY", "title": "Q2 results", "startsAt": "2026-10-16T10:30:00Z", "allDay": false, "source": "manual", "...": "..." } ]
```

### `GET /api/v1/events/risk?instrumentId=`

Scope: `market:read`. Market-level when no instrument.

```json
{ "level": "HIGH", "nextEvent": { "type": "RBI_POLICY", "title": "RBI MPC decision", "startsAt": "2026-10-07T04:30:00Z", "...": "..." }, "minutesTo": 45,
  "trigger": { "type": "RBI_POLICY", "title": "RBI MPC decision", "...": "..." }, "triggerMinutesTo": 45,
  "evidence": [ "RBI MPC decision (2026-10-07 10:00, in 45 min) → HIGH" ], "available": true }
```

### `POST /api/v1/events`

Scope: `strategies:write`. 201 with the event. `symbol` optional (a resolving symbol makes an INSTRUMENT event); `time`
optional (all-day otherwise).

```json
{ "type": "RESULTS", "symbol": "NSE:INFY", "title": "Q2 results", "date": "2026-10-16", "time": "16:00", "endDate": null, "confidence": 1.0 }
```

### `POST /api/v1/events/import` (`Content-Type: text/csv`)

Scope: `strategies:write`. Body: `type,symbol,title,date,time,end_date,confidence` rows. Response
`{ "imported": 12, "updated": 3, "errors": [ "line 4: unknown symbol NSE:NOPE" ] }`.

### `POST /api/v1/events/refresh?from=&to=`

Scope: `admin`. Pulls every enabled source (default window: a week back to the horizon ahead).
`{ "from": "...", "to": "...", "bySource": { "computed": 14, "curated": 31 }, "inserted": 45 }`.

Recommendations (`GET /api/v1/today`) now carry `eventRisk` (`LOW | MEDIUM | HIGH | UNKNOWN`), `nextEvent` ("Q2 results —
Today 16:00"), `regime` (trend × volatility) and may be `TRADE_WITH_CAUTION`; the header carries the market-level
`eventRisk` and `nextEvent`. `POST /signals/{id}/execute` of a signal whose strategy blocks on the event is rejected by
the risk pipeline (`eventRule` check).

## News and news bias (Phase 3, M3.4)

See `docs/news.md`. Scope: `market:read` unless noted.

### `GET /api/v1/news?instrumentId=&from=&limit=`

Stories of the trailing window (all, or those assessed for the instrument), newest first, with their assessments.

```json
[ { "id": "...", "sourceId": "...", "url": "https://...", "title": "Infosys wins $1.5 billion deal", "summary": "...", "publishedAt": "...",
    "assessments": [ { "instrumentId": "...", "sector": "IT", "relevance": 0.9, "direction": 0.8, "materiality": 0.6, "novelty": 1.0, "confidence": 0.9,
                       "eventType": "CONTRACT", "summary": "Infosys won a large multi-year deal.", "promptVersion": "news_classify_v1" } ] } ]
```

### `GET /api/v1/context/news-bias?instrumentId=`

```json
{ "instrumentId": "...", "computedAt": "...", "score": 0.58, "label": "BULLISH", "items": 2, "available": true,
  "evidence": [ "▲ Infosys won a large multi-year deal. (Economic Times Markets, 0.2 h ago, materiality 0.6, +0.39)", "Confirmed by 2 sources (×1.25)",
                "Price/volume reaction confirms the news (+1.20% on 1.6x volume)" ],
  "sources": [ { "itemId": "...", "title": "...", "url": "https://...", "source": "Economic Times Markets", "publishedAt": "...", "direction": 0.8, "materiality": 0.6,
                 "confidence": 0.9, "weight": 0.39, "summary": "..." } ] }
```

`available=false` (score 0, `NEUTRAL`, reason in `evidence`) when news or the LLM is disabled or the feed is stale.

### `GET /api/v1/news/sources`, `PUT /api/v1/news/sources/{id}` (`strategies:write`, body `{ "enabled": true, "reliability": 0.7 }`), `POST /api/v1/news/poll` (`admin`)

Recommendations (`GET /api/v1/today`) now carry `newsBias` (the score, null when unavailable).

## Context Card and decision states (Phase 3, M3.5)

See `docs/decisions.md`.

### `GET /api/v1/context/strategy?versionId=&instrumentId=`

Scope: `strategies:read`.

```json
{ "versionId": "...", "instrumentId": "...", "asOf": "...",
  "technicalFit": { "name": "Technical fit", "status": "GREEN", "value": "Strong", "delta": 8, "evidence": [ "3 of 3 entry conditions pass on the last closed bar (…)" ] },
  "marketRegime": { "name": "Market regime", "status": "GREEN", "value": "Favorable", "delta": 6, "evidence": [ "Current regime UP × NORMAL, …", "Preferred regime 'trending' is current: +3", "Similar regime UP × NORMAL: 34 trades, expectancy 0.42R vs 0.25R overall (+0.17R): +2" ] },
  "newsBias": { "name": "News bias", "status": "GREEN", "value": "Bullish +0.4", "delta": 1, "evidence": [ "▲ …" ] },
  "eventRisk": { "name": "Event risk", "status": "RED", "value": "High", "delta": -8, "evidence": [ "Q2 results (2026-10-16 16:00, results today) → HIGH" ] },
  "sector": { "name": "Sector", "status": "GREEN", "value": "Strong", "delta": null, "evidence": [ "Banking (INDEX:NIFTY BANK): +1.80% on the day, +1.10 points vs the index" ] },
  "nextEvent": "Q2 results — Today 16:00", "netImpact": -1, "items": [ "the five rows above, in order" ] }
```

Rows are `UNKNOWN` (value "Unknown", delta null, reason in evidence) when their service is disabled, stale or down.

Recommendations (`GET /api/v1/today`) now carry `cautions` (`[{ "code": "VIX_RISING", "message": "India VIX up 6.0% on the day" }]`, codes in
`docs/decisions.md`), `context` (the card above) and may be `TRADE_WITH_CAUTION`; the trading-window control is reported as `WAIT`
(under risks) rather than `AVOID`.

## LLM providers (Phase 4, M4.1)

See `docs/llm.md`.

### `GET /api/v1/agents/llm/status`

Scope: `market:read`. Providers with their circuit (`CLOSED | OPEN | HALF_OPEN`), profiles, and today's (IST) usage from the call log
against the daily cost cap. `keyPresent` only says whether the named env var holds a value; the key is never returned.

```json
{ "enabled": true,
  "providers": [ { "name": "primary", "type": "openai-compatible", "model": "gpt-4.1-mini", "configured": true, "keyPresent": true,
                   "circuit": "CLOSED", "consecutiveFailures": 0, "lastSuccessAt": "2026-09-10T04:01:00Z", "lastFailureAt": null, "lastError": null } ],
  "profiles": { "fast": { "provider": "primary", "model": null, "temperature": null, "maxTokens": null, "fallback": null } },
  "today": { "calls": 42, "failed": 1, "inputTokens": 51200, "outputTokens": 6100, "costPaise": 1830 },
  "dailyCostCapPaise": 20000, "budgetExceeded": false }
```

### `POST /api/v1/agents/llm/test`

Scope: `admin`. Body `{ "profile": "fast" }` (default `fast`). Sends "Reply with the single word OK." through the profile (logged in
`llm_call` with purpose `llm-test`). Failures are reported in the body, not as an HTTP error.

```json
{ "ok": true, "profile": "fast", "provider": "primary", "model": "gpt-4.1-mini", "text": "OK", "inputTokens": 14, "outputTokens": 1, "latencyMs": 420, "error": null }
```

## Agent tools (Phase 4, M4.2)

See `docs/agents.md` and the generated catalog `docs/agent-tools.md`.

### `POST /api/v1/auth/clients` with a preset

`{ "name": "research-bot", "preset": "research" }` (or `"execution"`) instead of `scopes`; giving both, neither or an unknown
preset is a 400.

### `GET /api/v1/agents/tools`

Authenticated. `[{ "name": "get_market_regime", "description": "…", "requiredScope": "market:read", "transactional": false,
"inputSchema": {…}, "outputSchema": {…} }, …]`

### `POST /api/v1/agents/tools/{name}`

Authenticated; the tool's own scope is checked. Optional headers `X-Agent-Session` (one of the caller's open sessions) and
`Idempotency-Key` (transactional tools). Body: the tool input.

```json
{ "tool": "calculate_position_size", "status": "OK", "output": { "quantity": 500, "riskPerUnit": 2, "totalRisk": 1000, "lotSize": 1, "notes": [] },
  "error": null, "details": [], "actionId": "0192…", "sessionId": "0192…", "latencyMs": 3 }
```

Refusals and errors are problem+json: `{ "type": "https://hejje.money/problems/agent-tool", "title": "FORBIDDEN", "status": 403,
"detail": "Tool get_account_risk requires scope risk:read", "tool": "get_account_risk", "toolStatus": "FORBIDDEN", "errors": [], "actionId": "…" }`.

### `GET /api/v1/agents/sessions?limit=50` · `GET /api/v1/agents/sessions/{id}`

Authenticated; own sessions (admin: all). The detail is `{ "session": {…}, "actions": [ { "tool", "input", "outputSummary", "scopeOk", "status", "error", "latencyMs", "correlationId", "ts" } ] }`.

### `POST /mcp`

MCP (streamable HTTP, JSON-RPC 2.0, JSON responses; `GET /mcp` is 405). Authenticated with an API key.

## Hejje AI (Phase 4, M4.3)

See `docs/agents.md`. All endpoints need an authenticated caller; tool calls inside use that caller's scopes.

### `GET /api/v1/agents/ai/status`

`{ "enabled": true, "llmEnabled": true, "profile": "reasoning", "followUpProfile": "fast", "maxSteps": 8, "reason": null }`
(`enabled: false` with a `reason` when the LLM or the chat is off).

### `POST /api/v1/agents/ai/ask`

Body `{ "question": "Why is nifty_orb ranked first?", "conversationId": null, "flow": null }` (`flow`: `why_ranked_first`,
`compare`, `working_today`; normally detected from the question). With `Accept: application/json`:

```json
{ "conversationId": "0192…", "messageId": "0192…", "answer": "nifty_orb v3 ranks first with score 87 [0192…] …",
  "grounding": { "verifiedNumbers": ["87"], "unverifiedNumbers": [], "citedIds": ["0192…"], "unknownIds": [] },
  "trace": [ { "actionId": "0192…", "tool": "get_strategy_rankings", "status": "OK", "requiredScope": "strategies:read", "latencyMs": 41, "error": null } ],
  "steps": 1, "profile": "reasoning", "flow": "why_ranked_first", "stepLimitReached": false }
```

503 when the chat is disabled, 502 when the LLM fails. With `Accept: text/event-stream` the same turn streams as
server-sent events: `tool` (a trace step), `delta` (`{ "step": 1, "text": "…" }`; a later step's text replaces an earlier
one), `done` (the turn above) or `error` (`{ "error": "…" }`).

### `GET /api/v1/agents/ai/conversations?limit=20` · `GET /api/v1/agents/ai/conversations/{id}`

The caller's conversations; the detail is `{ "conversation": {…}, "messages": [ { "seq", "role": "USER|ASSISTANT", "content", "flow", "profile", "grounding", "trace", "steps" } ] }`.

### `POST /api/v1/agents/llm/dev/fixture` (dev/test profiles only)

Admin. `{ "contains": "What is working today?", "response": "…" }` registers a canned answer on the `fixture` provider
(`hejje.llm.dev-fixture-endpoint=true`), for the web e2e.

## Agent proposals, approvals and policy (Phase 4, M4.4)

See `docs/agents.md` (proposals, approvals) and `docs/risk.md` (policy).

### `GET /api/v1/approvals?status=PENDING|APPROVED|REJECTED|EXPIRED|FAILED|ALL&limit=50` · `GET /api/v1/approvals/{id}`

Scope `orders:execute`. Newest first.

```json
{ "id": "0192…", "kind": "ORDER_NEW", "status": "PENDING", "mode": "PAPER", "intentId": "0192…", "instrument": "NSE:INFY",
  "requestedBy": "research-bot", "requestedByType": "CLIENT", "summary": "BUY 100 NSE:INFY MARKET MIS · entry ≈ 1500 · stop 1490.00 · target 1520.00 · risk ≈ 1000.00 rupees",
  "rationale": "Opening-range breakout", "proposal": { "quantity": 100, "riskOutcome": "APPROVED", "policyDecision": "REQUIRE_APPROVAL", "riskChecks": [ … ] },
  "risk": { "outcome": "APPROVED", "checks": [ … ] }, "policy": { "decision": "REQUIRE_APPROVAL", "rule": "agent_actions", "reason": "…" },
  "createdAt": "…", "expiresAt": "…", "decidedBy": null, "decidedAt": null, "decisionNote": null, "result": null }
```

### `POST /api/v1/approvals/{id}/approve` · `POST /api/v1/approvals/{id}/reject`

Scope `orders:execute`, header `Idempotency-Key` required (replays return the stored decision). Reject body
`{ "reason": "…" }`. Errors: 404 unknown, 409 not pending / expired / decided with another key, 403 an agent approving its
own proposal, 422 re-validation failed (`errors` lists the policy or risk failures) or execution refused.

### `GET /api/v1/risk/policies` · `PUT /api/v1/risk/policies/{id}`

`risk:read` / `risk:write`. `{ "rules": [ { "id", "name", "priority", "condition", "actions", "decision", "params", "enabled",
"description", "updatedAt", "updatedBy" } ], "defaultDecision": "REQUIRE_APPROVAL", "notes": [ … ] }`. `decision: "ALLOW"` is a 400
in this phase.

## Performance investigation (Phase 4, M4.5)

See `docs/analytics.md`. `market:read`; `from`/`to` default to month to date.

### `GET /api/v1/analytics/losses?from=&to=`

```json
{ "mode": "PAPER", "from": "2026-09-01", "to": "2026-09-30",
  "attribution": { "trades": 5, "winners": 2, "losers": 3, "netPnl": -50.00, "grossLosses": 600.00, "grossWins": 550.00,
    "dimensions": [ { "name": "family", "buckets": [ { "key": "MEAN_REVERSION", "trades": 3, "losers": 2, "netPnl": -350.00, "losses": 500.00, "lossSharePct": 83.3 } ] } ],
    "familyByTrend": [ { "key": "MEAN_REVERSION × STRONG_UP", "trades": 2, "losers": 2, "netPnl": -500.00, "losses": 500.00, "lossSharePct": 83.3 } ],
    "headline": "83.3% of losses came from mean reversion strategies during strong up sessions (2 of 2 trades lost)." } }
```

### `GET /api/v1/analytics/slippage?from=&to=` · `GET /api/v1/analytics/adherence?from=&to=`

`{ "slippage": { "entry": { "trades", "meanBps", "medianBps", "p90Bps", "worstBps", "costRupees" }, "exit": {…}, "totalCostRupees", "byStrategy": [...] } }` and
`{ "adherence": { "trades", "withAdherence", "meanAdherencePct", "fullAdherence", "setupInvalid", "manualExits", "netFullAdherence", "netPartialAdherence", "byStrategy": [...] } }`.

### `POST /api/v1/analytics/counterfactual`

Body `{ "from": "2026-09-01", "to": "2026-09-30", "exclude": { "families": ["MEAN_REVERSION"], "trends": ["STRONG_UP"] } }` (at least one category).

```json
{ "counterfactual": { "basis": "SIMULATED", "note": "Hypothetical: …", "actual": { "trades": 5, "netPnl": -50.00, "maxDrawdown": 600.00, … },
  "simulated": { "trades": 3, "netPnl": 450.00, "maxDrawdown": 100.00, … }, "excludedTrades": 2, "excludedNetPnl": -500.00, "netDifference": 500.00, "drawdownDifference": -500.00 } }
```

`GET /api/v1/analytics/pnl` also accepts `groupBy=family|eventContext|newsBias|exitReason`.

## Natural-language strategy drafts (Phase 4, M4.6)

### `POST /api/v1/strategies/drafts`

Scope `strategies:write`. Body `{ "description": "Buy NIFTY when …", "strategy": null }` (`strategy`: id or slug to draft that
strategy's next version). 201 when a DRAFT was created, 422 when no valid definition came out after three fixes, 503 when the
LLM is off.

```json
{ "created": true, "strategyId": "0192…", "slug": "nifty_orb_vwap_rv", "versionId": "0192…", "version": 1, "status": "DRAFT", "changeNote": "NL draft",
  "yaml": "name: nifty_orb_vwap_rv\n…", "rules": [ "Long on NIFTY, 5-minute bars.", "Enter when all of these hold at a bar close:",
  "• the close is above the 15-minute opening-range high", "• the close is above VWAP", "• relative volume (20 sessions) is above 1.5",
  "Stop: below the opening-range low.", "Target: 2R (2 × the risk).", "…" ],
  "parentYaml": null, "parentVersion": null, "attempts": [ { "iteration": 1, "yaml": "…", "errors": [] } ], "errors": [] }
```

## Strategy experiments (Phase 4, M4.7)

See `docs/backtesting.md`.

### `POST /api/v1/experiments` (`strategies:write`)

```json
{ "versionId": "0192…", "goal": "fewer false breakouts", "from": "2024-01-01", "to": "2026-08-31", "splits": { "type": "FIXED" },
  "variants": [ { "name": "vwap_filter", "description": "only above VWAP", "delta": { "entry_add": ["close > vwap"] } },
                { "name": "target_3r", "delta": { "target": { "value": 3 } } } ] }
```

201 with the experiment (QUEUED); it runs in the background.

### `GET /api/v1/experiments?versionId=` · `GET /api/v1/experiments/{id}` (`strategies:read`)

```json
{ "id": "0192…", "status": "DONE", "notes": [ "MULTIPLE_COMPARISONS: 3 variants were tested on the same data; …" ],
  "variants": [ { "name": "vwap_filter", "status": "DONE", "rank": 1, "score": 71.5, "verdict": "RECOMMENDED",
    "metrics": { "overall": { "trades": 140, "expectancyR": 0.3, "profitFactor": 1.5, "maxDrawdownR": 4.0, "winRate": 0.45, "netPnl": 12000.00 },
                 "outOfSample": { … }, "walkForwardStdR": null, "windows": 0, "qualityWarnings": [] },
    "warnings": [], "parameterCount": 6, "conditionCount": 4, "promotedVersionId": null } ] }
```

### `POST /api/v1/experiments/preview` (`strategies:read`) · `POST /api/v1/experiments/{id}/variants/{variantId}/promote` (`strategies:write`)

Preview body `{ "versionId", "delta" }` → `{ "valid", "errors", "yaml", "entryConditions", "parameterCount", "conditionCount" }`. Promote → 201 with the new
DRAFT version; 409 for the baseline, an unfinished or an already promoted variant.

## Live-vs-backtest drift (Phase 5, M5.1)

See `docs/analytics.md`. Deployments now carry `sizeMultiplier` (1.00 unless lowered by drift; scales the risk per trade).

### `GET /api/v1/strategies/{id}/drift` (`strategies:read`)

Every deployment of every version, newest version first: a fresh report, the stored state and the last five assessments.

```json
{ "strategyId": "0192…", "enabled": true, "deployments": [ {
  "report": { "deploymentId": "0192…", "version": 2, "mode": "PAPER", "enabled": true, "sizeMultiplier": 0.50, "status": "DEGRADING",
    "window": { "maxTrades": 30, "sessions": 60, "from": "2026-06-15", "to": "2026-09-10" },
    "live": { "trades": 30, "winRate": 0.37, "expectancyR": 0.34, "profitFactor": 1.1, "maxDrawdownR": 3.5 },
    "backtest": { "trades": 60, "winRate": 0.6, "expectancyR": 0.3, "profitFactor": 1.8, "maxDrawdownR": 4.0, "backtestId": "0192…", "split": "OUT_OF_SAMPLE" },
    "stats": { "winRatePValue": 0.0083, "expectancyLow": 0.05, "expectancyHigh": 0.62, "confidence": 0.9, "expectancyRatio": 1.14, "drawdownMultiple": 0.88 },
    "triggered": [ "win rate 37% vs 60% backtest is unlikely by chance (p = 0.008 < 0.05)" ], "evidence": [ "…" ] },
  "state": { "status": "DEGRADING", "actedStatus": "DEGRADING", "overrideStatus": null, … },
  "history": [ { "status": "DEGRADING", "actions": [ "ALERT", "LOWER_SCORE -10", "REDUCE_SIZE 1.00 -> 0.50" ], "at": "…" } ] } ] }
```

### `POST /api/v1/deployments/{id}/drift/override` (`strategies:write`)

Body `{ "reason": "reviewed: regime shift" }` (required, 400 when blank) → the stored state with `overrideStatus`. Suppresses drift actions for the
current status and anything no worse, sets the size multiplier back to 1.00 and audits `STRATEGY_DRIFT_OVERRIDDEN`; a paused deployment stays
paused (re-enable it with `PUT /deployments/{id}`). 409 when the status is HEALTHY or INSUFFICIENT_DATA.

## AUTO mode, autonomy 4-5 and policies (Phase 5, M5.2)

See `docs/execution.md` ("AUTO mode") and `docs/risk.md` (policy table).

### `POST /api/v1/strategies/{id}/versions/{v}/deployments`

`autonomyLevel` is 0-5. Levels 4-5 are refused on CONFIRM deployments (400) and, on AUTO deployments, until the version
has `hejje.auto.min-paper-trades` closed paper trades (409, "a new strategy version is never automatic"). New optional
`params` for autonomy 4-5: `daily_max_trades`, `daily_max_loss_rupees` (defaults `hejje.auto.default-*`).

### `GET /api/v1/risk/policies` · `PUT /api/v1/risk/policies/{id}`

The table now has `deployment_budget` (15, DEPLOYMENT_BUDGET_EXCEEDED → DENY) and `auto_strategy` (85, AUTO_ELIGIBLE →
ALLOW); `autonomy_above_phase` is gone. `PUT … {"decision": "ALLOW"}` is accepted only on an AUTO_ELIGIBLE rule (400
otherwise). Web: `/risk/policies`.

### Approvals held by the AUTO policy

A signal of an autonomy 4-5 deployment that the policy sends to a human appears in `GET /api/v1/approvals` as an
ORDER_NEW approval with `"requestedByType": "STRATEGY"`, `"requestedBy": "<strategy slug>"`, no `requestedBySession`,
the AUTO policy result in `policy` and its reason in `rationale`; it expires with the signal and is approved or rejected
like any other (approving executes the signal through the confirmation path).

Audit types: `AUTO_EXECUTED` (actor STRATEGY, with the policy decision, rule, trace and context), `AUTO_HELD` (outcome
HELD / DENIED / FAILED with the reason), `APPROVAL_CREATED` (a held signal's approval).

## Smart, basket and split orders (Phase 5, M5.3)

See `docs/execution.md`.

### `POST /api/v1/orders/intents` (`orders:execute`, Idempotency-Key)

New optional fields: `targetPosition` (instead of `side` + `quantity`; 400 when both are given) and `split`.

```json
{ "instrumentId": "0192…", "targetPosition": 100, "orderType": "MARKET", "product": "MIS", "stopPrice": "1495.00", "targetPrice": "1520.00" }
```

→ 201 `{ "plan": { "current": -50, "target": 100, "delta": 150, "side": "BUY", "quantity": 150 }, "order": { … } }`, or 200
`{ "noop": true, "plan": { … "delta": 0 } }`. With `"split": { "maxChildQuantity": 100, "delayMs": 300, "priceTolerancePct": 1.0,
"cancelOnMove": true, "deadlineSeconds": 900 }` → 201 `{ "split": { "id", "status": "WORKING", … } }`. A plain intent still
returns the order. Prices are strings, as elsewhere in the API.

### `GET /api/v1/orders/splits?limit=20` · `GET /api/v1/orders/splits/{id}` (`market:read`) · `POST /api/v1/orders/splits/{id}/cancel` (`orders:cancel`, Idempotency-Key)

A split: `{ "id", "side", "quantity", "policy", "status": "WORKING|COMPLETED|CANCELLED|EXPIRED|FAILED", "filledQuantity", "children",
"referencePrice", "deadline", "detail" }`; the detail view adds `children` (orders with `parentOrderId`).

### `POST /api/v1/baskets` (`orders:execute`, Idempotency-Key) · `GET /api/v1/baskets?limit=20` · `GET /api/v1/baskets/{id}` (`market:read`)

```json
{ "name": "pair", "policy": "ALL_OR_NOTHING", "rollback": "CLOSE_FILLED_LEGS", "deadlineSeconds": 600,
  "legs": [ { "instrumentId": "0192…", "side": "SELL", "quantity": 10, "stopPrice": "1505.00", "targetPrice": "1480.00", "hedgeFirst": true },
            { "instrumentId": "0192…", "side": "BUY", "quantity": 10, "orderType": "LIMIT", "limitPrice": "799.50", "stopPrice": "795.00", "targetPrice": "820.00" } ] }
```

→ 201 with the basket (`status`, `marginRequired`, `marginAvailable`, `detail`, `legs[]` with `sequence`, `executionOrder`,
`orderId`, `status`, `detail`, `rollbackOrderId`). The same key returns the same basket.

## Options (Phase 5, M5.4)

See `docs/options.md`.

### `GET /api/v1/instruments/options/expiries?underlying=NIFTY` · `GET /api/v1/instruments/options/chain?underlying=NIFTY&expiry=2026-09-15` (`market:read`)

```json
{ "underlying": "NIFTY", "expiry": "2026-09-15", "forward": 25010.00, "forwardSource": "NFO:NIFTY:FUT:2026-09-29", "yearsToExpiry": 0.0142,
  "atmStrike": 25000.00, "pcrOi": 1.12, "pcrVolume": 0.94, "maxPain": 25000.00, "notes": [],
  "rows": [ { "strike": 25000.00,
    "call": { "instrumentId": "0192…", "symbol": "NFO:NIFTY:OPT:2026-09-15:25000:CE", "lotSize": 75, "last": 120.30, "oi": 81000, "volume": 12000,
              "iv": 0.121, "delta": 0.52, "gamma": 0.0011, "vega": 11.4, "theta": -16.9, "stale": false },
    "put": { … } } ] }
```

### `GET /api/v1/options/positions?limit=20` · `GET /api/v1/options/positions/{id}` (`market:read`)

An options position: `{ "id", "underlying", "direction", "underlyingStop", "basketId", "status": "PENDING|OPEN|CLOSING|CLOSED|FAILED",
"legs": [ { "sequence", "symbol", "side", "quantity", "stopPrice", "targetPrice", "hedgeFirst", "entryPrice", "exitPrice" } ],
"combinedStop", "combinedTarget", "closeReason", "realized" }`.

`POST /api/v1/signals/{id}/execute` on a signal of an options strategy returns the options position (201) instead of an order.


## Notifications (Phase 5, M5.5)

Rules and channels: `docs/notifications.md`.

### `GET /api/v1/notifications?limit=50` · `POST /api/v1/notifications/{id}/read` (`market:read`)

The in-app inbox, newest first: `{id, type, severity, title, body, data, dedupeKey, createdAt, readAt}`. New ones are also
pushed on `/ws/events` as `{"type": "notification", "data": {id, notificationType, severity, title, body}}`.

### `GET /api/v1/notifications/{id}/deliveries` (`admin`)

The delivery log of one notification: `[{channel, status, detail, createdAt, sentAt}]`; status `QUEUED`, `SENT`,
`FAILED` (detail: the error), `SKIPPED` (detail: what the channel is missing), `DIGESTED` (held by the rate limit),
`DIGEST_SENT`.

### `GET /api/v1/notifications/rules` · `PUT /api/v1/notifications/rules/{id}` (`admin`)

`[{id, eventType, channel, minSeverity, enabled, updatedAt, updatedBy}]`; the update takes `{"enabled": false}` and/or
`{"minSeverity": "WARNING"}` (audited `NOTIFICATION_RULE_UPDATED`).

### `GET /api/v1/notifications/channels` · `POST /api/v1/notifications/test` (`admin`)

Channel status `[{channel, configured, status, perMinute}]` (never the Telegram token). The test sends a `TEST`
notification through every channel with an enabled TEST rule and returns `{notification, deliveries}` once sent.

## External webhooks (Phase 5, M5.5)

Flow, authentication and the TradingView template: `docs/webhooks.md`.

### `GET /api/v1/webhooks` · `POST /api/v1/webhooks` · `PUT /api/v1/webhooks/{id}` · `POST /api/v1/webhooks/{id}/rotate` (`admin`)

```json
POST /api/v1/webhooks
{ "name": "tv-orb", "strategyVersionId": "…", "allowedInstruments": ["NSE:INFY"], "authMode": "HMAC" }
→ 201 { "webhook": {id, name, authMode, strategyVersionId, enabled, allowedInstruments, createdAt, createdBy, updatedAt, lastReceivedAt},
        "secret": "whsec_…", "url": "/api/v1/webhooks/{id}", "note": "The secret is shown only now; …" }
```

No `strategyVersionId` makes a `MANUAL_EXTERNAL` webhook; `authMode` is `HMAC` (default) or `PASSPHRASE`. The secret is
returned only here and by `rotate` (the old one stops working at once); `PUT` takes `{enabled, allowedInstruments}`.
Option-leg strategies are refused. Audited `WEBHOOK_CREATED` / `WEBHOOK_UPDATED`.

### `GET /api/v1/webhooks/{id}/deliveries?limit=50` (`admin`)

`[{id, receivedAt, status, detail, signalId, approvalId}]`; status `ACCEPTED`, `REJECTED` or `REPLAYED`.

### `POST /api/v1/webhooks/{id}` (no login: the signature or passphrase authenticates)

Body: a signal intent (`docs/webhooks.md`). Answers `202 {result: "ACCEPTED", detail, signalId?, approvalId?}`;
`401` bad signature/passphrase or timestamp outside the window, `409` replay, `403` disabled, `404` unknown webhook,
`400` not JSON, `422` validation or mapping refused (`detail` says why). Audited `WEBHOOK_RECEIVED`.

## Second broker, accounts and active/standby (Phase 5, M5.6)

### `GET /api/v1/server/executor` (`market:read`)

`{instance, role: ACTIVE|STANDBY|NOT_REQUIRED, required, held, epoch, activeInstance, activeEpoch, expiresAt, holdUntil}`.

### `POST /api/v1/server/failover` (`admin`)

Body `{"confirmation": "FAILOVER"}` (400 otherwise). On the active instance: releases the executor lease →
`200 {released: true, instance, epoch, holdUntil, message}` (audit `EXECUTOR_FAILOVER`); on a standby `409`.

Order endpoints (`POST /orders/intents`, modify, cancel, and what builds on them) answer **503** "Not the active
executor" on a standby, and an intent accepted just before a takeover is REJECTED with `NOT_ACTIVE_EXECUTOR` without
reaching the broker.

### `GET /api/v1/broker/accounts` (`market:read`) · `POST /api/v1/broker/accounts/{id}/activate` (`admin`)

`{adapter, active, accounts: [{id, broker, accountId, label, active, createdAt, updatedAt, activatedAt, activatedBy}]}`.
Activation makes the account the only active one (audit `BROKER_ACCOUNT_ACTIVATED`); for an account of another broker
the answer carries a `note` that a restart with that adapter is needed. Logins register accounts
(`BROKER_ACCOUNT_REGISTERED`).

### Changes

- `GET /api/v1/broker/callback` also accepts Dhan's `tokenId` (in place of Kite's `request_token`);
  `POST /api/v1/broker/login?request_token=` also accepts a Dhan access token (`docs/broker-dhan.md`).
- `GET /api/v1/server/latency` rows carry `broker` (the broker timers are tagged with it).
- Reconciliation issues carry `broker`.

## Simulation (Phase 7, M7.2)

SIM instances only (`hejje.mode=SIM`, `docs/simulation.md`); scope `sim:run` (users hold every scope). One session at a
time.

### `POST /api/v1/sim/sessions`

Body: `{ "dates": ["2026-09-08"] | "from": "2026-08-03", "to": "2026-08-07", "instruments": ["NSE:INFY"] | "universe":
"nifty50", "capitalRupees": 1000000, "riskPerTradeRupees": 2000, "lossHaltRupees": 5000, "maxPositions": 5, "bots": [] }`.
Validates the days (trading days) and that every instrument has M1 candles or recorded ticks for each; resets the
simulated ledger, the paper broker (capital), the market pipeline and the strategy runners; applies the risk settings to
the SIM limits; starts simulation time at the first day's 09:15. 201 with the session (`PAUSED`, step 0). 400 on a bad
spec or missing data; 409 while another session is active. `bots` must be empty until M7.3.

### `POST /api/v1/sim/sessions/{id}/control`

Body `{ "action": "play" | "pause" | "step" | "cancel", "speed": "1" | "10" | "60" | "300" | "MAX" }` (either may be
omitted). `step` replays one minute of a paused session and returns after it; `pause` returns once the step in progress
has finished. 409 when the session is not active or (for `step`) is playing.

### `GET /api/v1/sim/sessions/{id}` · `GET /api/v1/sim/sessions?limit=20`

```json
{ "id": "…", "state": "PLAYING", "speed": "60", "day": 1, "days": 3, "sessionDate": "2026-09-08", "step": 42, "progress": "42/375",
  "fills": 0, "frictionPaid": { "paise": 0 }, "netPnl": { "paise": 0 }, "resultHash": null, "error": null, "spec": { "…": "…" },
  "createdBy": "admin", "createdAt": "…", "finishedAt": null }
```

`fills`, `frictionPaid` (transaction costs), `netPnl` and `resultHash` (SHA-256 over the fills) are set when the session
finishes (`DONE`, `CANCELLED` or `FAILED`).

## Bots (Phase 7, M7.3)

See `docs/bots.md`. `POST /api/v1/bots` (`strategies:write`) registers a bot (EXTERNAL/LLM: generated backing strategy
`bot_<name>`; STRATEGY: an existing `strategyId`); `GET /api/v1/bots` · `GET /api/v1/bots/{id}` (with `stats`)
(`strategies:read`); `POST /api/v1/bots/{id}/enabled`; `POST /api/v1/bots/{id}/decisions` (`bot:decide`, body
`{ "pointId", "decisions": [ { "instrument", "action", "stop", "target", "confidence", "thesis", "stage", "scores",
"candidates" } ] }`, returns the recorded decisions); `GET /api/v1/bots/{id}/decisions?limit=`. WebSocket
`/ws/bot?token=&bot=` (`bot:decide`): decision points out, replies in. `POST /api/v1/auth/clients` accepts preset
`bot` (`market:read strategies:read bot:decide`). SIM sessions accept `"bots": [ { "botId" } ]` and report
`warnings` (an LLM bot on days before its knowledge cutoff).

