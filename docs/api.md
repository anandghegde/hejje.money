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
