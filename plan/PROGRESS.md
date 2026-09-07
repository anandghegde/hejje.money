# Hejje — Progress log

One entry per completed milestone. Newest at the bottom.

| Milestone | Date | Commit / PR | Notes, deviations, follow-ups |
|---|---|---|---|
| — | — | — | Plan created 2026-09-07 from hejje_prd.md Draft v1 |
| M0.1 | 2026-09-08 | (this commit) | Gradle 8.14.5 wrapper, Spring Boot 3.5.16, Modulith 1.4.13, Java 21 toolchain via foojay (local JDK 17 runs Gradle). `event_publication` registry table lives in `V1__baseline.sql` (Modulith schema init disabled). Health endpoint public until M0.3. Wrapper URL validation disabled (`validateDistributionUrl=false`) because the JVM could not HEAD services.gradle.org from this machine; Testcontainers on Colima needs `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. |
| M0.2 | 2026-09-08 | (this commit) | `common`: Money/Price/Quantity/Ids (own UUID v7), enums, HejjeClock + JdbcHolidayCalendar (NSE 2026 weekday holidays seeded in V2, from Zerodha's calendar; re-verify against the NSE circular), events (HejjeEvent/EventMeta, TickBus contract), CorrelationIdFilter (UUID only; non-UUID headers are replaced), ApiExceptionHandler (RFC 7807), log redaction (pattern converter + JSON provider). `audit`: audit_event table with append-only trigger (V3), AuditService, `GET /api/v1/audit`. `MutableClock` lives in test sources. AuditEventType includes Phase 0 auth/egress types up front. Follow-up: correlation ids from TUI/agents must be UUIDs (documented in docs/events.md). |
