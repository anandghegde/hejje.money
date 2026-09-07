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
