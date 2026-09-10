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
(active/standby with controlled failover since M5.6, below). Under the test profile the lease does not gate execution (many test contexts share one DB).

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

## Smart, basket and split orders (Phase 5, M5.3)

- **Smart (position-aware) intents** (PRD 33): `POST /orders/intents` with `targetPosition` instead of `side` + `quantity`.
  `ExecutionPlanner` takes the current net position for (server mode, instrument, product, strategy; no strategy = the
  manual book) and submits the delta (−50 → +100 is BUY 150; equal is a no-op answered with `200 {noop: true, plan}`).
- **Baskets** (PRD 34, `POST /baskets`): the broker's margin for all legs together (`getOrderMargins`) is compared with
  available funds first; a basket that does not fit fails before any leg. Legs then go through the normal pipeline
  (validation, risk, kill switch, gate, idempotency key `basket:<id>:<n>`) one at a time, hedge legs first, each waited
  on until filled before the next (explicit legging, no naked leg while a hedge is outstanding). ALL_OR_NOTHING stops at
  the first failed leg (refused, rejected, or unfilled at the deadline, which is cancelled); with `CLOSE_FILLED_LEGS` the
  filled legs are closed with market orders in reverse placement order (reason `BASKET_ROLLBACK`, exposure-reducing).
  BEST_EFFORT places every leg (PARTIAL when some fail). Statuses: PENDING, EXECUTING, COMPLETED, PARTIAL, FAILED,
  ROLLED_BACK, EXPIRED; each leg records its order, state, detail and placement order. Audit `BASKET_CREATED` /
  `BASKET_FINISHED`. Agents ask with the `submit_basket_intent` tool (a BASKET_NEW approval; approving submits the basket).
- **Splits** (PRD 35): `split {maxChildQuantity, delayMs, priceTolerancePct, cancelOnMove, deadlineSeconds}` on an
  intent (or automatically above `hejje.execution.planning.auto-split-above`). The whole intent is validated and
  risk-checked once first (a split never works around a limit such as max quantity); children of at most
  `maxChildQuantity` (whole lots) are then placed through the pipeline one at a time, `delayMs` apart, each waited on,
  as orders whose `parentOrderId` is the split. Each child is risk-checked again, except `reentryCooldown`: the
  account's cooldown counts from the last fill on the instrument, so it would stop every child after the first; the
  whole intent passed it, and the child's risk decision records the check as `waived: child of split …`. An adverse move beyond `priceTolerancePct` from the reference (limit
  price, else the last price at the start) ends the split (`cancelOnMove`) or pauses it until the price returns; the
  deadline cancels a working child and ends the split (EXPIRED). Audit `SPLIT_STARTED` / `SPLIT_FINISHED`.
- Baskets and splits interrupted by a restart are marked FAILED on startup; the orders they had placed are ordinary
  orders that reconciliation tracks. Web: Orders page "Baskets" and "Split orders"; TUI `hejje baskets [id]`, `hejje splits`.

## Active/standby and failover (Phase 5, M5.6)

The executor lease (`executor_lease`, PRD 43) is the ownership primitive: the active instance renews it every
`hejje.execution.lease.heartbeat` (10 s); a standby takes it over once it has not been renewed for `ttl` (30 s), or at
once after a controlled failover. Every change of owner increments `epoch`, the fencing token. Right before any order
reaches the broker (place, modify, cancel) the engine re-reads the lease and refuses unless this instance still owns it
at the epoch it acquired and the lease is live (`NotActiveExecutor`: the order is REJECTED with `NOT_ACTIVE_EXECUTOR`,
REST answers 503); an unreadable lease refuses too. So a paused or partitioned former active never submits once a
standby has taken over (`ExecutorFailoverIT`: races, partitions, pauses, failovers and a 600-step random schedule —
at most one submitter per epoch). A standby serves reads and does not poll orders, reconcile, resolve unknown orders or
carry out kill-switch actions; when it acquires the lease it runs the startup bootstrap (reconcile, restore in-flight
orders) before executing, and an instance that loses the lease goes back to waiting for it.

`GET /server/executor` shows this instance's role (ACTIVE, STANDBY, or NOT_REQUIRED under the test profile), the active
instance and epoch. `POST /server/failover {"confirmation": "FAILOVER"}` (admin, also `hejje failover` and the Server
page) on the active instance releases the lease; the standby acquires it on its next heartbeat and the old active does
not contend for `failover-hold` (2 min). Deployment: `deploy/RUNBOOK.md` §9.

**Broker dimension**: `broker.call{op, broker}` and `broker.ack{broker}` carry the broker code, `GET /server/latency`
returns it per timer, and reconciliation issues record the broker they were found on.

**Broker accounts** (`broker_account`): every login registers its account; the first becomes the active transactional
account; `POST /broker/accounts/{id}/activate` (admin) switches (one active at a time, enforced by a partial unique
index). The `brokerAccount` readiness check blocks orders while the connected account is not the active one; switching
to another broker's account needs a restart with that adapter.

