# Execution: reconciliation, recovery, rate limiting, latency, gating

## Reconciliation (PRD 38)

ReconciliationService compares Hejje against broker truth every 30 s during the session, at startup, and on demand
(POST /execution/reconcile). Local order state is corrected from the broker (source=RECONCILIATION); broker orders Hejje
never created are imported (source=EXTERNAL, audit EXTERNAL_ORDER_IMPORTED). A position quantity mismatch is a CRITICAL
reconciliation_issue; when hejje.reconciliation.pause-on-critical is set it trips the kill switch, and the reconciliation
readiness check stays red until the issue is resolved (POST /execution/reconciliation-issues/{id}/resolve).

## Startup recovery (PRD 63)

ExecutorBootstrap runs on startup: acquire the single-writer executor lease, then (once the broker session is up)
reconcile broker state and restore in-flight orders (UNKNOWN / *_PENDING / SUBMITTING polled from the broker), then
enable execution. Each step feeds the readiness checks. A second process cannot acquire the lease and stays read-only
(standby failover is Phase 5). Under the test profile the lease does not gate execution (many test contexts share one DB).

## Rate limiting (PRD 39)

BrokerRateLimiter (Bucket4j) sits in RateLimitedBrokerAdapter, the primary BrokerAdapter. Order operations
(place/modify/cancel) fail fast with RATE_LIMITED when the per-second/minute/day bucket is exhausted; orders are never
queued. Reads wait up to read-wait-millis for a token. Metrics: order tokens available, rate-limit rejections.

## Latency (PRD 44)

Micrometer timers broker.call{op}, broker.ack, risk.evaluate and the HTTP request timer carry p50/p95/p99, exposed at
GET /server/latency.

## Live-trading gate

LiveTradingGate.check(intent) is the single place the pipeline asks whether an order may reach the broker now. It
composes the broker session, every readiness check (lease, clock, egress IP, broker, reconciliation, market-data
staleness, bootstrap) and the kill switch, and returns the reasons. Exposure-reducing intents bypass the kill switch.
