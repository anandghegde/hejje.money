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
cd server && ./gradlew bootRun
curl -s localhost:8080/api/v1/server/health
```

The server starts in `PAPER` mode with the `dev` profile. `CONFIRM` and `AUTO` modes refuse to start
unless the `prod` profile is active.

## Test

```bash
cd server && ./gradlew clean build
```

Integration tests start PostgreSQL through Testcontainers; Docker must be running.
