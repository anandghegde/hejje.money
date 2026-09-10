# Risk engine and kill switch

The risk engine (`money.hejje.risk`) sits above every broker call and is authoritative and deterministic (PRD sections
31, 32). The execution pipeline calls `RiskEngine.evaluate(intent)` after validation and before placing the order; a
rejection is a 422 with the failing checks, and the decision is persisted to `risk_decision`. No client can bypass it.

## Inputs

`AccountSnapshotBuilder` assembles an `AccountSnapshot`: realized P&L (sum of position realized), unrealized P&L
(positions marked with `QuoteCache`), open position count, gross exposure, trades today, consecutive losing round-trips
today (reconstructed from trades), broker funds/margin, net quantity and last trade time per instrument. The engine is a
pure function of the intent, this snapshot and the `RiskLimits` for the mode.

## Controls (`RiskControls`, each a pure check)

Always applied (also to exposure-reducing intents): `brokerConnected`, `readiness`.

Limit checks (skipped for exposure-reducing intents — reason `POSITION_CLOSE`/`STRATEGY_EXIT`/`KILL_SWITCH`, or a side
opposite to an open position): `killSwitch`, `dailyLoss`, `realizedLoss`, `totalLoss`, `openPositions`, `tradesPerDay`,
`riskPerTrade` (|entry−stop|×qty), `quantity`, `notional`, `marginUtilization` (broker `getOrderMargins`, else notional),
`minRewardRisk` (when a target is set), `mandatoryStop`, `maxStopDistance`, `tradingWindow` (no new trades after 14:45),
`averagingDown`, `reentryCooldown` (10 min), `consecutiveLosses`.

## Kill switch

`kill_switch` (one row per mode). `POST /risk/kill-switch` with `STOP_NEW_ORDERS`, `CANCEL_ALL_OPEN` or
`CLOSE_ALL_POSITIONS` (the last requires the confirmation string `CLOSE ALL`). Every action sets `stop_new_orders`;
the destructive actions publish `KillSwitchActivated`, which the execution module handles by cancelling open orders and
closing positions. When the daily loss limit is breached, the engine auto-trips the switch (audit `KILL_SWITCH_ENABLED`,
reason `DAILY_LOSS`). Re-arm with `DELETE /risk/kill-switch` (admin). A local CLI kill (`--kill`) is planned in the
RUNBOOK for M1.6.

Closing orders are always allowed while the switch is at `STOP_NEW_ORDERS`; only new or increasing exposure is blocked.

## Stop suggestion

`StopSuggester.suggest(side, entry, atr, maxStopDistancePct, tick)` proposes an initial stop 1.5 × ATR(14) from the
entry (1% of the entry when no bars are available), clamped to `maxStopDistancePct` and rounded to the tick towards
the entry, so a suggested stop always passes `mandatoryStop` and `maxStopDistance`. `RiskService.suggestStop` feeds it
the last ten days of M5 bars and the last traded price; exposed at `GET /risk/stop-suggestion`, which the web manual
order form uses to prefill the stop when an instrument is resolved or the side changes (a stop the user typed is kept).

## Position sizing

`PositionSizer.size(entry, stop, riskMoney, lotSize, maxQty)` returns a whole number of lots such that
|entry−stop|×qty ≤ riskMoney, capped at `maxQty`. Exposed at `POST /risk/position-size`.

## Approval policy (Phase 4, M4.4)

`risk.policy.PolicyEngine` answers `ALLOW | REQUIRE_APPROVAL | DENY` for an action (`ORDER_NEW`, `ORDER_MODIFY`,
`ORDER_CANCEL`, `POSITION_CLOSE`) by an actor (`USER`, `AGENT`, `STRATEGY`) with its context (deployment autonomy level,
event risk, score, whether the version is LIVE). Rules live in `policy_rule` (seeded from PRD 49), are evaluated by
priority, and the first enabled rule whose actions and condition match decides; no match means `REQUIRE_APPROVAL`.

| Priority | Rule | Condition | Actions | Decision |
|---|---|---|---|---|
| 10 | `daily_loss_block` | net P&L today ≤ −`lossLimitPct`% (100) of the daily loss limit | new orders | DENY |
| 15 | `deployment_budget` | the deployment's daily budget is used up (entries ≥ `daily_max_trades`, or gross realized loss ≥ `daily_max_loss_rupees`; M5.2) | new orders | DENY |
| 30 | `agent_needs_prepare_level` | an agent on a strategy with autonomy < 2 (research, recommend) | new orders | DENY |
| 40 | `event_risk_high` | the instrument's event risk is HIGH | new orders | REQUIRE_APPROVAL |
| 50 | `new_strategy_version` | the version is not promoted for the mode (not LIVE; for AUTO paper rehearsal: not PAPER or LIVE) | new orders | REQUIRE_APPROVAL |
| 60 | `agent_actions` | actor AGENT | all | REQUIRE_APPROVAL |
| 70 | `manual_orders` | actor USER | all | REQUIRE_APPROVAL |
| 80 | `score_below_80` | score < 80 | new orders | REQUIRE_APPROVAL |
| 85 | `auto_strategy` | AUTO_ELIGIBLE: actor STRATEGY at autonomy ≥ `minLevel` (4), version qualified for automation, score known (M5.2) | new orders | ALLOW |
| 90 | `strategy_signals` | actor STRATEGY | new orders | REQUIRE_APPROVAL |

ALLOW reaches only the local user and strategy signals of a qualified deployment at autonomy 4-5 (`docs/execution.md`,
"AUTO mode"); agents and everything else are capped at REQUIRE_APPROVAL (Automation Level 3), and only AUTO_ELIGIBLE
rules may be edited to ALLOW (PRD 49 "Approved AUTO strategy → Auto"; the web `/risk/policies` page edits rules). The
M4.4 rule `autonomy_above_phase` was removed by V28. Autonomy levels are stored per deployment (0–5; 4–5 on PAPER and
AUTO deployments only); agent proposals without a deployed strategy use `hejje.agent.approvals.account-autonomy-level`
(3), and a named strategy without an enabled deployment counts as level 0. The engine is consulted for agent proposals and again when they are approved; manual orders
and signal executions keep their own confirmation steps.

`GET /api/v1/risk/policies` (`risk:read`) lists the rules; `PUT /api/v1/risk/policies/{id}` (`risk:write`,
`{ "enabled", "decision", "priority", "params" }`) edits one (`POLICY_UPDATED` audit).
