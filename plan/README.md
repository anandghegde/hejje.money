# Hejje — Implementation Plan

Source: `hejje_prd.md` (Draft v1, September 2026).
This plan converts the PRD into ordered milestones that a coding agent can execute one at a time.

Files:

| File | Content |
|---|---|
| `README.md` | How to use, locked decisions, repo layout, cross-cutting rules, definition of done, phase map |
| `phase-0-foundation.md` | Repo, kernel, auth, deployment skeleton, static-IP verification |
| `phase-1-execution.md` | Broker adapter, market data, orders, risk, reconciliation, paper mode, Web v1, TUI v1 |
| `phase-2-strategy.md` | Strategy DSL, indicators, backtester, library, score, signal engine, Today, Lab |
| `phase-3-context.md` | Regime, Pulse, events, news bias, context engine, decision states |
| `phase-4-agent.md` | LLM providers, tool surface, Hejje AI, confirmed execution, NL builder, experiments |
| `phase-5-automation.md` | Drift, AUTO mode, smart/basket/split orders, options, notifications, webhooks, HA |
| `phase-6-strategy-research.md` | Data audit and baselines, new price/volume indicators and strategies, 9:20 iron fly, strategy bake-off |
| `phase-7-bot-harness.md` | SIM mode and clock, historical session replay, bot protocol, harness TUI screen, leaderboard |
| `phase-8-market-context.md` | NIFTY 500 daily universe, RS/A-D ratings and groups, market condition, bases and pivots, daily and session analogs, screener and stock page, pre-registered validation |
| `phase-9-jev-and-gaps.md` | Jev typed-decision API, calibration, news and risk events on Jev, order-book/flow features, Jev bot and signal check, trade causes, pace and loss-streak allowance, passive entries |
| `phase-10-web-design.md` | Web design pass: tokens and light/dark theme, shared components and app shell, every page restyled, phone layouts for the trading pages |
| `phase-11-swing.md` | Swing trading: CNC positions overnight, Zerodha GTT stops, overnight risk, entries from the Phase 8 base setups, multi-day backtests, PAPER run (gated on H5) |

---

## 1. How to use this plan with a coding agent

- Execute **one milestone per agent session** (e.g. `M1.4`). Milestones inside a phase are ordered; each lists its dependencies.
- Every milestone has: **Goal**, **Tasks**, **Acceptance criteria**, **Verification**, **Out of scope**.
- The agent must run the Verification commands and paste the output before claiming the milestone is done.
- If the agent finds the milestone under-specified, it must state the assumption in the PR description rather than silently choosing.
- Keep a running `plan/PROGRESS.md` (milestone id, date, PR/commit, notes, deviations). The agent updates it at the end of each milestone.

Suggested prompt template:

```text
You are implementing milestone <ID> of the Hejje plan.
Read plan/README.md fully (rules and decisions are binding), then plan/<phase file>.md section <ID>.
Read plan/PROGRESS.md for what already exists. Do not re-do earlier milestones.
Implement only the tasks listed. Write the tests listed under Verification. Run them.
When done: summarize what was built, paste verification output, list any deviations or assumptions,
and append an entry to plan/PROGRESS.md.
```

---

## 2. Locked decisions and assumptions

These follow the PRD (§66B, §66C) where it is explicit. Where the PRD leaves a choice open, the choice below is made so the agent never has to guess. Override here before starting Phase 0 if you disagree.

| Area | Decision | Why |
|---|---|---|
| Backend | Java 21, Spring Boot 3.3+, virtual threads enabled | PRD §66B |
| Build | Gradle (Kotlin DSL), single Gradle project | Simple; one `bootJar` deployment |
| Module boundaries | **Spring Modulith** (`@ApplicationModule` per package, `ApplicationModules.verify()` in tests, event publication registry for durable in-process events) | Enforces modular monolith; gives the typed in-process event model the PRD asks for without Kafka |
| Persistence | PostgreSQL 16, Flyway, **Spring Data JDBC** (not JPA) | Explicit SQL-shaped aggregates, no lazy-loading surprises |
| IDs | UUID v7 (`uuid` column) | Sortable, no coordination |
| Money | `Money` value type backed by `long` paise; prices `BigDecimal` scale 2 validated against tick size; never `double` for money | PRD §66E, determinism |
| Time | DB columns `timestamptz` (UTC); all business logic through an injectable `HejjeClock` with `Asia/Kolkata` helpers | Testability, IST session logic |
| Historical data | Parquet files partitioned by timeframe/instrument/year, queried via **DuckDB JDBC**; operational candles (last ~15 sessions) in Postgres | PRD §66B |
| Kite client | Official `javakiteconnect` (`com.zerodhatech.kiteconnect:kiteconnect`, latest on Maven Central) wrapped entirely inside `ZerodhaKiteAdapter`; replace with direct REST + WebSocket only if it blocks | Fastest path; adapter isolates it |
| Dev without broker | `FakeBrokerAdapter` (deterministic in-memory broker) + recorded tick replay | Kite has no sandbox; tests must never hit the broker |
| Execution modes | Server runs in exactly one global mode: `PAPER` or `CONFIRM` (Phase 1) or `AUTO` (Phase 5). No mixing in one process | PRD §26, §50 keep results separate |
| Web | React 18+, TypeScript, Vite, React Router, TanStack Query + Table, `lightweight-charts` for charts | PRD §66B |
| TUI | Go 1.22+, Cobra for subcommands, Bubble Tea + Bubbles + Lip Gloss for the dashboard | PRD §66B |
| Auth | Single local user; password login → 15-minute JWT access token + refresh token (httpOnly cookie); **client credentials** (API keys) with scopes for TUI and agents | PRD §48 |
| Secrets | Env vars only (`HEJJE_*`); Kite access token encrypted at rest with AES-GCM using `HEJJE_ENCRYPTION_KEY`; log redaction filter | PRD §48.1 |
| TLS / proxy | Caddy in front of the server (auto TLS) | PRD §66B |
| Testing | JUnit 5, Testcontainers (Postgres), WireMock (Kite HTTP), Spring Modulith verify, Vitest + Playwright smoke, `go test` | — |
| CI | GitHub Actions: server tests, web build+test, tui test, on every PR | — |
| LLM | Off by default (`hejje.llm.enabled=false`); provider abstraction introduced in Phase 3 (news), completed in Phase 4 | PRD §66C, §66E |
| Users | Single user, personal system | PRD §77 |

Kite Connect facts the plan relies on (verify against current Kite docs before Phase 1):

- Login: redirect to `https://kite.zerodha.com/connect/login?v=3&api_key=…`; callback carries `request_token`; exchange with `checksum = SHA-256(api_key + request_token + api_secret)` for an `access_token`.
- Access tokens expire daily (early morning next day); a manual login is required each trading day. No automation of the login itself.
- Instruments master: daily CSV download; refresh once per day before market open.
- Historical candles: minute intervals limited to ~60 days per request; rate limit ~3 req/s; requires the historical-data add-on.
- Rate limits (approx.): quotes 1 req/s, historical 3 req/s, orders 10 req/s and ~200/min, ~3000 orders/day, other calls 10 req/s.
- Order updates: WebSocket `order` messages and optional HTTP postback (checksum `SHA-256(order_id + order_timestamp + api_secret)`).
- WebSocket ticker: up to 3 connections per token, up to 3000 instruments per connection; modes `ltp`, `quote`, `full`.
- MIS positions are auto squared off by the broker from ~15:20; strategies must force-exit before that.
- Static IP registration with Zerodha is required for API order placement (SEBI/NSE 2025 framework).

---

## 3. Repository layout

```text
hejje/
  server/            Java 21 Spring Boot modular monolith (Gradle)
    src/main/java/money/hejje/
      common/        shared kernel: Money, Price, Quantity, ids, HejjeClock, events base
      system/        health, egress-IP verification, clock drift, readiness, latency
      auth/          users, JWT, client credentials, scopes
      audit/         append-only audit events
      instruments/   instrument master + broker mappings
      market/        ticks, candles, quote cache, historical store, indicators
      broker/        BrokerAdapter contract, session lifecycle
        zerodha/     ZerodhaKiteAdapter
        fake/        FakeBrokerAdapter
        paper/       PaperBrokerAdapter
      orders/        intents, order state machine, trades, positions
      execution/     execution pipeline, rate limiter, reconciliation, bootstrap, gate
      risk/          risk engine, limits, kill switch, policies
      strategy/      definition DSL, versions, deployments          (Phase 2)
      signals/       live signal engine, exit management            (Phase 2)
      backtest/      backtester, cost model, experiments            (Phase 2)
      scoring/       Hejje Score, adjusters, comparison             (Phase 2)
      analytics/     P&L attribution, post-trade review             (Phase 2)
      regime/        regime classifier                              (Phase 3)
      events/        event calendar, event risk                     (Phase 3)
      news/          news ingestion and bias                        (Phase 3)
      context/       context engine, decision states                (Phase 3)
      llm/           provider abstraction                           (Phase 3/4)
      agent/         tools, sessions, approvals                     (Phase 4)
      notify/        notifications, webhooks                        (Phase 5)
    src/main/resources/db/migration/   Flyway (V<n>__*.sql)
  web/               React + TypeScript + Vite
  tui/               Go CLI/TUI (`hejje`)
  research/          Python research workers (optional, Phase 2+)
  strategies/        bundled strategy YAML definitions
  config/            cost model, universes, aliases, macro events (YAML)
  deploy/            Dockerfile, docker-compose.*.yml, Caddyfile, systemd, backup scripts
  docs/              ADRs, API notes, symbol format, score methodology, runbooks
  plan/              this plan + PROGRESS.md
```

API base path: `/api/v1`. WebSocket endpoints: `/ws/market`, `/ws/events`.

---

## 4. Cross-cutting rules (binding for every milestone)

1. **No broker secret leaves the server.** Never in API responses, web bundle, TUI config, logs, LLM prompts, or test fixtures.
2. **Every transactional endpoint requires `Idempotency-Key`** (place, modify, cancel, close, kill switch, approvals). Replays return the original response.
3. **Every state change emits an `AuditEvent`** with correlation id, actor (USER/AGENT/SYSTEM/STRATEGY), client source, and linked ids (strategy, signal, intent, order, broker ref). Audit rows are immutable (DB trigger).
4. **Money and prices are never floats.** Use `Money` (paise) and `BigDecimal` prices.
5. **Modules talk only through their public API classes or events.** `ApplicationModules.verify()` must pass.
6. **Migrations only move forward.** Never edit an applied migration.
7. **Feature flags for anything external**: LLM, news, events, notifications default off. The trading core must run with all of them off (PRD §66E).
8. **Tests never touch the real broker.** `HEJJE_MODE` defaults to `PAPER`; live requires explicit config.
9. **Closing orders are always allowed** when the kill switch is at `STOP_NEW_ORDERS`; only new/increasing exposure is blocked.
10. **Deterministic core**: same candles + same strategy version = same signals. Backtester and live evaluator share the same indicator and rule code.
11. **Explainability**: every score, decision, and risk rejection carries a structured evidence list; prose is templated from evidence, never invented.
12. **Surgical scope**: implement the milestone, nothing speculative. Note follow-ups in `PROGRESS.md` instead of building them.

---

## 5. Definition of done (every milestone)

- Code compiles; all existing tests still pass; new tests listed under Verification exist and pass.
- Flyway migrations added for new tables; `ApplicationModules.verify()` passes.
- New endpoints documented in `docs/api.md` (path, scope, request/response example).
- Config keys documented in `docs/config.md` with defaults.
- Audit events emitted for new state changes.
- No secrets, no `double` for money, no TODO left without a `PROGRESS.md` follow-up entry.
- `plan/PROGRESS.md` updated.

---

## 6. Phase map

| Phase | Outcome | Exit criteria |
|---|---|---|
| 0 Foundation | Bootable server, kernel types, auth, audit, deploy skeleton, static-IP check | `docker compose up` on the VM shows `/api/v1/server/health` with static IP verified; CI green |
| 1 Execution | Manual + paper trading end to end through the daemon with risk, kill switch, reconciliation, Web v1, TUI v1 | One live 1-share round trip and one paper round trip with full audit trail; restart recovery test; duplicate-request test |
| 2 Strategy | Strategy DSL, backtester with costs, 6 strategies, Score v1, live signals, Today screen, Lab v1, attribution | Signal → Today → confirm → fill → close → post-trade review in PAPER; replay parity backtester vs live |
| 3 Context | Regime, Pulse, events, news bias, TRADE/CAUTION/WAIT/AVOID with evidence | Today shows regime/event/news adjustments; all context services can be down without affecting execution |
| 4 Agent | LLM providers, typed tools, Hejje AI, agent-prepared orders with human confirmation, NL builder, experiments | Agent prepares an order that a human approves in TUI; agent without scope is denied; NL strategy lands as DRAFT only |
| 5 Automation | Drift, AUTO mode, smart/basket/split orders, options, notifications, webhooks, second broker, HA lease | AUTO executes only policy-eligible strategies; drift pauses a degraded strategy; split-brain test passes |

Sizing hint per milestone: **S** (one session), **M** (two to three), **L** (several sessions; split by task list if needed).
