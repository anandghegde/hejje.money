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
orders:execute orders:cancel positions:close risk:read risk:write admin`. Users hold every scope; clients
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
