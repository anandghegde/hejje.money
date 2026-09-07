# Phase 5 — Advanced Automation

Goal of the phase (PRD §71): drift monitoring, AUTO mode with explicit policies, smart/basket/split orders, options and multi-leg, notifications and webhooks, a second broker adapter to validate the abstraction, and active/standby execution. Each milestone is independently shippable; order below is the recommended sequence.

---

## M5.1 Live-vs-backtest drift monitor  (size: M)

**Tasks.**
1. `analytics.drift`: per deployment (PAPER and LIVE separately) compute trailing-N (default 30 trades and 60 sessions) win rate, expectancy, PF, max DD vs the validated OOS backtest; statistical signal: binomial test on win rate and bootstrap CI on expectancy; status `HEALTHY | WATCH | DEGRADING | FAILED` per thresholds in `config/drift.yaml`.
2. Configured actions per status (PRD §25): lower score (adjuster `DriftAdjuster` −15..0), alert, size multiplier (deployment `size_multiplier`), move LIVE → PAPER, pause; audit `STRATEGY_PAUSED` with statistics. Actions are only taken when thresholds are met; manual override with reason.
3. `GET /strategies/{id}/drift`, strategy detail panel (PRD §25 table), TUI `hejje strategy <id>` shows status.

**Verification.** Tests with synthetic trade streams crossing each threshold; pause fires once, not repeatedly.

---

## M5.2 AUTO mode, autonomy levels 4–5, approval policies  (size: L)

**Tasks.**
1. Server mode `AUTO` allowed only in `prod` with `hejje.auto.acknowledged=true`; banner `● LIVE · AUTO`. Deployment autonomy 4 (auto-execute entries when all deterministic checks pass) and 5 (lifecycle actions: trailing, exits, re-entries within policy, self-pause on drift) enforced by the policy engine; per-deployment daily budgets (max trades, max loss) in addition to account limits.
2. Policy table extended per PRD §49 and inspectable/editable in web `/risk/policies`; "new strategy version → never auto" enforced by requiring `paper_trades >= hejje.auto.min-paper-trades` and `VALIDATED` before level ≥ 4.
3. Signal engine executes eligible signals through the same pipeline with actor `STRATEGY`; approval required paths still create approvals (e.g. event risk HIGH).
4. Safety tests: chaos test killing the server mid-trade → on restart, protective stop present, runner restored, no duplicate entry; kill switch stops AUTO immediately; LLM offline has no effect on AUTO.

**Verification.** `./gradlew test --tests '*Auto*' --tests 'money.hejje.risk.policy.*'`

---

## M5.3 Smart, basket, and split orders  (size: L)

**Tasks.**
1. Smart (position-aware) intents: `POST /orders/intents` with `target_position` (PRD §33) → `ExecutionPlanner` computes the delta from the current position for that instrument/product/strategy; zero delta → no-op response.
2. Baskets (PRD §34): `basket(id, policy {ALL_OR_NOTHING, BEST_EFFORT}, rollback {NONE, CLOSE_FILLED_LEGS}, deadline, status)`, `basket_leg(order, sequence, hedge_first)`; execution respects leg ordering (hedges first for multi-leg options), tracks partial completion, applies rollback on failure; risk evaluates the basket as a whole (net margin via broker basket-margin API when available).
3. Split (PRD §35): `split_policy{max_child_qty, delay_ms, price_tolerance, cancel_on_move, deadline}` on intents exceeding `max_quantity` or configured liquidity thresholds; children tracked under a parent order; cancel remaining on tolerance breach.
4. Web/TUI: basket status view, split progress; tools `submit_basket_intent` (approval required).

**Verification.** Planner tests (PRD §33 example: −50 → +100 requires BUY 150); basket rollback with fake broker failing leg 2; split respects delay and deadline.

---

## M5.4 Options analytics and multi-leg strategies  (size: L)

**Tasks.**
1. Option chain service from instruments + quotes (`GET /instruments/options/chain?underlying=&expiry=`), IV and greeks (Black-76 on futures price; Java implementation with tests against known values), max pain and PCR as context.
2. DSL extension: `legs[]` for defined-risk spreads, straddle/strangle, iron condor/fly with strike selection rules (`atm`, `delta ~0.3`, `offset points`), leg ordering and hedge-first, per-leg stop/target and combined P&L stop.
3. Options risk: max premium at risk, max lots, defined-risk mandatory unless explicitly allowed per deployment, margin check via broker basket margin before submission, expiry-day restrictions.
4. Backtester support for multi-leg with option historical data (requires options candles backfill; document data limits) — otherwise mark options strategies `PAPER`-only until enough paper history exists.
5. Two initial strategies: directional option buying on index ORB signal; bull/bear vertical spread. Web: option chain view, leg builder in Lab.

**Verification.** Greeks tests; basket execution for a spread in PAPER; risk blocks a naked short without allowance.

---

## M5.5 Notifications and external webhooks  (size: M)

**Tasks.**
1. `notify` module: channels in-app (WS + web push), email (SMTP), Telegram bot; `notification_rule(event_type, channel, min_severity, enabled)` defaults per PRD §58 list; digest/rate limiting per channel; delivery log.
2. Webhooks (PRD §59): `webhook(id, name, secret, strategy_version_id | MANUAL_EXTERNAL, enabled, allowed_instruments)`; `POST /webhooks/{id}` with HMAC-SHA256 signature check and timestamp replay window → `SignalIntent{instrument, direction, entry?, stop?, target?, riskRupees?}` → validation → mapped strategy rules → risk → policy (approval unless the deployment is AUTO and level ≥ 4). TradingView alert JSON template documented in `docs/webhooks.md`.
3. Web settings for channels and webhooks; TUI `hejje notify test`.

**Verification.** Signature tests (valid, tampered, replayed); webhook signal shows in Today and creates an approval in CONFIRM mode.

---

## M5.6 Second broker adapter and active/standby execution  (size: L)

**Tasks.**
1. Implement one more `BrokerAdapter` (pick a broker with a sandbox or clear docs, e.g. Dhan or Upstox) covering the full contract; conformance test suite (`BrokerAdapterContractTest`) that every adapter, including fake and paper, must pass; instrument mapping for the new broker; per-broker rate-limit config; `broker_account` table allowing one active transactional broker at a time.
2. Active/standby (PRD §43): the `executor_lease` from M1.6 becomes the ownership primitive; standby instance runs API/read paths and heartbeats; controlled failover command `POST /server/failover` (admin) and CLI; secondary static IP documented in the RUNBOOK; split-brain test (two instances, lease expiry, network partition simulation) proves at most one submits orders.
3. Latency and reconciliation dashboards include broker dimension.

**Verification.** Contract suite green for all adapters; split-brain test.

---

## Backlog (deliberately not planned)

Mobile app, multi-user/tenant, strategy marketplace, social/copy trading, advisory features, HFT-grade latency, independent market-data vendors, Kubernetes. Revisit the regulatory model before any of these (PRD §77).
