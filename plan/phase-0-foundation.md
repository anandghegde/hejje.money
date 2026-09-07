# Phase 0 — Foundation

Goal of the phase: a bootable, deployable, secured skeleton with the kernel types, audit log, auth, and static-IP verification that every later milestone relies on. No trading logic yet.

Milestones: M0.1 → M0.2 → M0.3 → M0.4 (strictly ordered).

---

## M0.1 Repository scaffold and CI  (size: M)

**Goal.** Monorepo with a running Spring Boot server, Postgres via Docker, Flyway, Spring Modulith module layout, and CI.

**Tasks.**
1. Create the layout from `README.md §3` (empty dirs may hold a `.gitkeep`). Add root `README.md` with run instructions and `.gitignore` (Gradle, node, Go, `.env`, `data/`).
2. `server/`: Gradle Kotlin DSL, Java 21 toolchain, Spring Boot 3.3+. Dependencies: `spring-boot-starter-web`, `-websocket`, `-security`, `-validation`, `-data-jdbc`, `-actuator`, `flyway-core` + `flyway-database-postgresql`, `postgresql`, `spring-modulith-starter-core`, `spring-modulith-starter-jdbc` (event publication registry), `spring-modulith-starter-test`, `micrometer-registry-prometheus`, `jackson-datatype-jsr310`. Test: JUnit 5, AssertJ, Testcontainers (`postgresql`), WireMock.
3. Root package `money.hejje`. Create modules with `package-info.java` + `@ApplicationModule`: `common` (shared kernel, `type = OPEN`), `system`, `auth`, `audit`. Add `ModularityTests` calling `ApplicationModules.of(HejjeApplication.class).verify()`.
4. `application.yml` with profiles `dev`, `test`, `prod`. Add `HejjeProperties` (`@ConfigurationProperties("hejje")`): `mode` (`PAPER|CONFIRM|AUTO`, default `PAPER`), `timezone` (`Asia/Kolkata`), `data-dir`. Fail fast on startup if `mode=CONFIRM|AUTO` and profile is not `prod`.
5. Enable virtual threads (`spring.threads.virtual.enabled=true`). JSON logging in `prod`, plain in `dev`.
6. `deploy/docker-compose.dev.yml`: Postgres 16 with a named volume. `V1__baseline.sql` (empty baseline or schema placeholder).
7. `GET /api/v1/server/health` (public for now) returning `{ status, mode, version, time }`. Actuator health on `/actuator/health`.
8. GitHub Actions workflow `ci.yml`: `./gradlew build` with Testcontainers; placeholders for web and tui jobs (added in M1.8/M1.9).

**Acceptance criteria.**
- `docker compose -f deploy/docker-compose.dev.yml up -d && ./gradlew bootRun` starts and `/api/v1/server/health` returns mode `PAPER`.
- `ModularityTests` passes; CI green.

**Verification.**
```bash
cd server && ./gradlew clean build
curl -s localhost:8080/api/v1/server/health
```

**Out of scope.** Any domain table, auth, web, tui.

---

## M0.2 Shared kernel, event bus, audit log  (size: M)

**Goal.** Value types, clock, event base classes, and the immutable audit log.

**Tasks.**
1. `common` value types (records, immutable, validated):
   - `Money` (long paise; `of("1500.00")`, `plus/minus/times`, `toRupeesString()`; rejects fractional paise).
   - `Price` (BigDecimal scale 2; `alignedTo(tickSize)` check; `Price.of("24930.05")`).
   - `Quantity` (int > 0; lot-size validation helper).
   - `CorrelationId` (UUID v7), `Ids.newId()`.
   - Enums shared across modules: `Side {BUY, SELL}`, `Exchange {NSE, BSE, NFO, BFO, MCX, INDEX}`, `Product {MIS, CNC, NRML}`, `OrderType {MARKET, LIMIT, SL, SL_M}`, `ExecutionMode {PAPER, CONFIRM, AUTO}`, `ActorType {USER, AGENT, STRATEGY, SYSTEM, WEBHOOK}`.
2. `HejjeClock`: wraps `java.time.Clock`; `nowIst()`, `today()`, `isTradingDay(LocalDate)`, `sessionWindow(LocalDate)` (09:15–15:30), `isSessionOpen()`, `minutesToClose()`. Holidays from table `exchange_holiday(date, exchange, name)`; seed NSE 2026 holidays via migration (`V2__holidays.sql`). Tests use a `MutableClock`.
3. Events: `HejjeEvent` base (id, occurredAt, correlationId). Two channels, documented in `docs/events.md`:
   - **Durable domain events** via Spring `ApplicationEventPublisher` + Modulith `@ApplicationModuleListener` (persisted in the event publication registry; used for orders, signals, positions).
   - **High-volume market events** via an in-process `TickBus` (plain listener registry, no persistence) — created in M1.3, declare the contract now.
4. Correlation filter: read/generate `X-Correlation-Id`, echo it back, put into MDC. All logs include it.
5. `audit` module: table `audit_event(id, ts, type, actor_type, actor_id, correlation_id, strategy_id, signal_id, order_intent_id, order_id, broker_ref, client_source, payload jsonb)`. DB trigger raises on `UPDATE`/`DELETE`. `AuditService.record(AuditEvent)` public API; `AuditEventType` enum seeded with PRD §47 values. `GET /api/v1/audit?from=&to=&type=&orderId=` (paged).
6. Global `@ControllerAdvice` returning RFC 7807 `application/problem+json`; validation errors listed per field.
7. Log redaction: a Logback `MessageConverter`/filter that masks values of keys matching `(?i)(api_key|api_secret|access_token|password|authorization)`.

**Acceptance criteria.**
- Unit tests: `Money` arithmetic and rounding, `Price` tick alignment, clock helpers around holidays/weekends/session edges.
- Attempting `UPDATE audit_event` in a Testcontainers test throws.
- A log line containing `access_token=abc` is written masked (test captures appender output).

**Verification.**
```bash
cd server && ./gradlew test --tests 'money.hejje.common.*' --tests 'money.hejje.audit.*'
```

---

## M0.3 Authentication, client credentials, scopes, rate limiting  (size: M)

**Goal.** Every API call is authenticated and scoped; TUI and agents use revocable keys.

**Tasks.**
1. Tables: `app_user(id, username, password_hash, created_at)`; `refresh_token(id, user_id, token_hash, expires_at, revoked_at)`; `client_credential(id, name, key_prefix, secret_hash, scopes text[], created_at, expires_at, revoked_at, last_used_at)`.
2. Bootstrap: on first start with no user, create `admin` from `HEJJE_ADMIN_PASSWORD` (Argon2id hash). Refuse to start in `prod` if the env var is missing and no user exists.
3. Endpoints: `POST /auth/login` → `{accessToken}` (JWT HS256/EdDSA, 15 min, claims: sub, scopes=all) + refresh cookie (httpOnly, Secure, SameSite=Strict, 12 h); `POST /auth/refresh`; `POST /auth/logout`. `GET /auth/me`.
4. Client credentials: `POST /auth/clients {name, scopes, expiresAt}` → returns plaintext key once, format `hejje_<prefix>_<secret>`; `GET /auth/clients`; `DELETE /auth/clients/{id}` (revoke). Requires `admin` scope. `Authorization: Bearer hejje_…` resolves to a principal with the stored scopes.
5. Scopes exactly as PRD §48.2: `market:read strategies:read strategies:write orders:prepare orders:execute orders:cancel positions:close risk:read risk:write admin`. Enforce with method security `@PreAuthorize("hasAuthority('SCOPE_orders:execute')")`. Add `ScopeCatalog` so later modules reference constants.
6. WebSocket handshake auth: token passed as `?token=` on `/ws/*` (short-lived JWT only; API keys rejected for WS unless scope `market:read`).
7. Rate limiting per principal with Bucket4j (default 20 req/s burst 40; transactional paths 5 req/s). 429 with `Retry-After`.
8. Audit `AUTH_LOGIN`, `AUTH_LOGIN_FAILED`, `CLIENT_CREATED`, `CLIENT_REVOKED`.
9. Make `/api/v1/server/health` require `market:read` except a public minimal `/api/v1/server/ping`.

**Acceptance criteria.**
- Security tests: no token → 401; token missing scope → 403; revoked key → 401; expired access token → 401 and refresh works; rate limit → 429.
- Secrets hashed; plaintext key never stored.

**Verification.**
```bash
cd server && ./gradlew test --tests 'money.hejje.auth.*'
```

---

## M0.4 Deployment skeleton, static-IP verification, clock and readiness  (size: M)

**Goal.** Deployable to the static-IP Linux VM with TLS; server knows whether it is on the approved IP.

**Tasks.**
1. `deploy/Dockerfile` (multi-stage Gradle build → JRE 21 runtime, non-root). `deploy/docker-compose.prod.yml`: `hejje` (env from `.env`, volume `data/`), `postgres` (volume), `caddy` (ports 80/443, `Caddyfile` with reverse proxy and auto TLS for `HEJJE_DOMAIN`). `deploy/systemd/hejje.service` as an alternative for bare-metal.
2. `deploy/backup.sh` (`pg_dump` nightly, keep 14) and `deploy/RUNBOOK.md`: provisioning the VM, registering the static IP with Zerodha, creating the Kite app with redirect URL `https://<domain>/api/v1/broker/callback`, required env vars, first login, upgrade procedure, restore procedure.
3. `system` module:
   - `EgressIpVerifier`: every `hejje.system.egress.check-interval` (default 5 min) resolve public IP from two configured HTTP resolvers (default `https://api.ipify.org` and `https://checkip.amazonaws.com`); compare with `hejje.execution.expected-ips` (list). State `VERIFIED | MISMATCH | UNKNOWN` (both resolvers failed). Publish `EgressIpStatusChanged` event and audit on change. In `dev` profile default to `SKIPPED`.
   - `ClockDriftChecker`: compare local time against the `Date` header of an HTTPS HEAD to a configured host; drift > 2 s → `DEGRADED`.
   - `ExecutionReadiness` aggregate (`system` module public API) with named checks; Phase 1 adds broker/session/reconciliation checks. Exposes `isExecutionEnabled()` and reasons.
4. `GET /api/v1/server/health` returns the PRD §41 shape: `executionServer, staticIp, broker, marketData, database, clockSync, riskEngine, orderQueue` (unknown checks reported as `NOT_CONFIGURED` until later milestones fill them).
5. Prometheus scrape endpoint enabled; `docs/observability.md` listing metrics.

**Acceptance criteria.**
- `docker compose -f deploy/docker-compose.prod.yml up -d` on a VM serves `https://<domain>/api/v1/server/ping`.
- With a wrong `expected-ips`, health shows `staticIp: MISMATCH` and `ExecutionReadiness.isExecutionEnabled()` is false.
- Unit tests for verifier state machine with mocked resolvers.

**Verification.**
```bash
cd server && ./gradlew test --tests 'money.hejje.system.*'
docker compose -f deploy/docker-compose.prod.yml config   # validates compose file
```

**Phase 0 exit.** All four milestones done; CI green; `PROGRESS.md` lists the VM hostname, expected IPs (not secrets), and Kite app redirect URL.
