# Hejje HTTP API

Base path: `/api/v1`. All responses are JSON. Errors use RFC 7807 `application/problem+json` (from M0.2).

## Server

### `GET /api/v1/server/health`

Scope: public (M0.1); requires `market:read` from M0.3.

```json
{ "status": "UP", "mode": "PAPER", "version": "0.1.0-SNAPSHOT", "time": "2026-09-08T00:00:00Z" }
```

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

Scope: authenticated (M0.2); `admin` from M0.3. `from`/`to` are ISO-8601 instants (`to` exclusive),
`type` is an `AuditEventType`, `size` is at most 500. Newest first.

```json
{ "content": [ { "id": "0192...", "ts": "2026-09-08T04:00:00Z", "type": "ORDER_SUBMITTED", "actorType": "USER",
    "actorId": "admin", "correlationId": "0192...", "orderId": "0192...", "clientSource": "tui",
    "payload": { "symbol": "NSE:INFY", "qty": 10 } } ],
  "page": 0, "size": 50, "total": 1 }
```
