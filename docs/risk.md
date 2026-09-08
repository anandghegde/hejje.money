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

## Position sizing

`PositionSizer.size(entry, stop, riskMoney, lotSize, maxQty)` returns a whole number of lots such that
|entry−stop|×qty ≤ riskMoney, capped at `maxQty`. Exposed at `POST /risk/position-size`.
