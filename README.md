# Hejje

Personal intraday trading decision and execution platform for Indian markets (Zerodha Kite Connect).
Product requirements: `hejje_prd.md`. Implementation plan and progress: `plan/`.

## Layout

| Path | Content |
|---|---|
| `server/` | Java 21 Spring Boot modular monolith (Gradle, Spring Modulith, Spring Data JDBC, Flyway) |
| `web/` | React + TypeScript web UI (Phase 1) |
| `tui/` | Go terminal UI (Phase 1) |
| `deploy/` | Docker Compose, Dockerfile, Caddy, systemd, backups |
| `docs/` | API notes, config reference, ADRs, runbooks |
| `plan/` | Milestone plan and `PROGRESS.md` |

## Run locally

Requires Docker and a JDK 17+ to run Gradle (the build provisions a Java 21 toolchain automatically).

```bash
docker compose -f deploy/docker-compose.dev.yml up -d
cd server && HEJJE_ADMIN_PASSWORD=dev-password ./gradlew bootRun
curl -s localhost:8080/api/v1/server/ping
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"dev-password"}' | jq -r .accessToken)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/server/health | jq
```

The server starts in `PAPER` mode with the `dev` profile; egress-IP and clock-drift checks report
`SKIPPED` there. `CONFIRM` and `AUTO` modes refuse to start unless the `prod` profile is active.
`HEJJE_ADMIN_PASSWORD` is only read on the first start, when no user exists yet.

Production deployment (Docker Compose with Caddy TLS, backups, runbook): see `deploy/RUNBOOK.md`.
API reference: `docs/api.md`. Configuration: `docs/config.md`. Metrics and logs: `docs/observability.md`.

## Test

```bash
cd server && ./gradlew clean build
```

Integration tests start PostgreSQL through Testcontainers; Docker must be running. With Colima, export
`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
