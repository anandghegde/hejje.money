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

## AUTO mode and autonomy levels 4-5 (Phase 5, M5.2)

`hejje.mode=AUTO` starts only under the `prod` profile **and** with `hejje.auto.acknowledged=true`; the web banner reads
`● LIVE · AUTO` (TUI likewise). The `money.hejje.auto` module decides every new signal of a deployment at autonomy 4-5:

1. Deployments: autonomy 0-5; 4-5 only on AUTO deployments (live) or PAPER deployments (a rehearsal with simulated
   fills), never on CONFIRM. An AUTO deployment at 4-5 also needs `hejje.auto.min-paper-trades` (30) closed paper
   trades of the version (and the version must be LIVE, like every live deployment).
2. For each signal (listener on the signal event, plus a sweep on startup and every `hejje.auto.sweep`): skipped unless
   the deployment is at autonomy 4-5, enabled and in the server's mode, and the signal is still actionable. With the
   kill switch at STOP_NEW_ORDERS it is refused at once (audit `AUTO_HELD`, outcome DENIED; no order is attempted).
3. The policy engine decides with actor STRATEGY and the deterministic context: autonomy, qualification (promoted for
   the mode: PAPER or LIVE for paper, LIVE plus paper history for live), the deployment's daily budget (entries today
   vs `daily_max_trades`, gross realized P&L vs `daily_max_loss_rupees`), the instrument's event risk and the latest
   Hejje Score. The seeded table: daily loss → DENY, deployment budget → DENY, event risk HIGH → approval, version not
   promoted → approval, score below 80 → approval, `auto_strategy` (AUTO_ELIGIBLE: strategy, autonomy ≥ 4, qualified,
   scored) → ALLOW, otherwise strategy signals → approval. Only AUTO_ELIGIBLE rules may ALLOW; agents never get ALLOW.
4. Autonomy 4 automates the first entry per instrument and day; a re-entry needs a human. Autonomy 5 also re-enters
   within the budget and pauses itself when drift reaches `hejje.auto.self-pause-drift` (DEGRADING). Stops, trailing,
   targets and exits of every executed position are deterministic runner actions at every level (Phase 2).
5. ALLOW → `SignalService.executeAuto`: the same sizing, validation, risk, kill switch, gate and idempotency as a human
   confirmation, submitted as actor STRATEGY with the signal's idempotency key (`auto:<signal>`), so a redelivered or
   re-swept signal can never enter twice; audit `AUTO_EXECUTED` carries the policy decision and trace.
   REQUIRE_APPROVAL → an approval in the inbox requested by the strategy (`requestedByType: STRATEGY`, no agent session,
   expiring with the signal); approving it executes the signal through the normal confirmation path. DENY → the signal
   is BLOCKED with the rule and reason.

Nothing on this path calls an LLM. After a restart the signal engine re-attaches protective stops (M2.6) and the sweep
re-offers only signals that are still actionable.

