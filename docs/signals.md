# Live signal engine and exit management (`money.hejje.signals`)

The signal engine evaluates deployed strategies on live candles with exactly the code the backtester uses
(indicators, condition evaluator, level maths), produces signals with validity windows, and manages the resulting
positions deterministically. It runs without any LLM and keeps working when context services are down.

## Runners

`SignalEngine` keeps one `StrategyRunner` per enabled deployment × instrument in the current execution mode. Runners
are (re)created from the deployments table at startup and on every `DeploymentChanged` event: a paused deployment stops
producing signals but its runner keeps managing an open position; a deleted deployment's runner is dropped once flat.
A runner warms its indicator context up from `hejje.signals.warmup-days` of history and subscribes the instrument to
the market stream. All runner work happens on one engine thread (bus events, order events, service calls), so a
runner never sees two things at once.

## Runner rules (mirror of the backtester's replay, docs/backtesting.md)

On every closed candle of the runner's timeframe, in this order:

1. A signal from the previous bar that has not been executed is **expired** ("next bar closed"), unless the definition
   sets `signal_validity_minutes`.
2. With an open position: the bar's extremes are checked. A crossed stop with **no live stop order** fires a software
   market exit (`SOFTWARE_STOP`, audited as `STOP_MISSING`); a touched target fires a market exit (`TARGET`). Ticks are
   checked the same way between bars, so a target is taken when the price touches it, not only at the bar close.
3. The indicator context sees the bar.
4. With an open position: trailing stop (the broker-side stop is **modified**, audit `STOP_MODIFIED`; when the modify
   is refused the stop is cancelled and re-placed), VWAP target refresh, `exit` rules (from the first bar that did not
   start before the fill), `max_holding_minutes`, and the force exit at `force_exit_time`. Each exit cancels the stop
   order and submits a `STRATEGY_EXIT` market order.
5. When flat, not paused, inside `trade_window` and under `max_trades_per_day`: the entry rules are evaluated. A pass
   creates a `Signal` (side, reference = bar close, stop and target from the definition, per-unit risk, evidence with
   observed values, `validUntil`), audited as `SIGNAL_CREATED` and `STRATEGY_RECOMMENDED`, and published as
   `SignalGeneratedEvent`.

## From signal to position

- `POST /signals/{id}/prepare` sizes the order on the **current quote** (else the signal's reference) with
  `PositionSizer` and the deployment's `risk_rupees` (else the definition's, else `hejje.signals.default-risk-rupees`),
  returns the proposed intent and a **dry-run** `RiskDecision`; the signal becomes `PREPARED`. Nothing is submitted.
- `POST /signals/{id}/execute` (scope `orders:execute`, `Idempotency-Key` required) is the human confirmation in
  CONFIRM/PAPER mode: a pending `strategy_position` is recorded, the entry is submitted through the execution engine
  (validation, risk, kill switch, gate all apply), the signal becomes `EXECUTED` and `USER_APPROVED` is audited. Refusals
  leave the signal `ACTIVE` with the reason.
- On the entry fill the position is `OPEN` and a **broker-side protective stop** (`SL-M`, role `STOP`, reason
  `STRATEGY_STOP`, exposure-reducing so the kill switch never blocks it) is placed, audited as `STRATEGY_STOP_PLACED`.
  If the stop is refused, cancelled or rejected, it is re-placed once; failing that the software monitor exits at market.
- Targets are software-managed (market exit on touch); a resting target order would need an OCO the broker does not
  offer, and the exit fill cancels the remaining stop (audit `POSITION_CLOSED` with the reason).
- Fills and order state changes arrive as durable order events and are matched to positions by order id, or through
  the order's intent → signal when the fill beats the write-back.
- `POST /signals/{id}/skip` marks a signal `SKIPPED`; a scheduled sweep expires stale signals (`SIGNAL_EXPIRED`).

## Restart

`strategy_position` persists every managed position. On startup the engine re-creates the runners, re-attaches open
positions (restoring the trades-per-day counter) and verifies each stop order is still live at the broker; a missing
stop is re-placed (`STOP_MISSING` then `STRATEGY_STOP_PLACED`).

## Parity

`ParityTest` feeds the same recorded sessions to a runner with the simulated execution port (fills at the bar close,
stops/targets resolved intrabar with the conservative rule) and to the backtester with `fillModel=BAR_CLOSE`, for every
bundled strategy, and asserts identical signal times, entry prices, stop and target levels, quantities and exit
reasons. Live fills differ from the replay only by the market fill itself (next tick after confirmation) and by slippage.

## Metrics

`signal.to.ack` (execute → broker acknowledgement) and `signal.to.fill` (execute → fill), p50/p95/p99, next to the
PRD 44 timers on `GET /server/latency` sources.
