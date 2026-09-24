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

## Jev signal check (plan M9.5)

With `hejje.jev.signal-check.enabled` (and Jev on), every new strategy signal (live, PAPER, SIM; never the
backtester; not a bot's own entries) gets one Jev call with the stage-2 questions (`bot-stage2`) on its instrument and
side, on its own thread within the Jev deadline. The answer is appended to the signal's evidence as an annotation
`{ "condition": "jevCheck", "jevCheck": { "setup", "p", "composite", "version", "agrees", "reason" } }` (it is not a
rule, so it never counts as passed or failed), and recorded for calibration as `signal-check` (`ENTRY_1R` with the
signal's stop). Jev **agrees** when the setup matches the side (`long_continuation` / `short_continuation`) and
P(setup) reaches the set's `min-setup-prob`.

It never creates, sizes or blocks a trade. `hejje.jev.signal-check.gate`:

| Gate | Effect of a disagreement |
|---|---|
| `off` (default) | shown only: a `⚠ Jev disagrees …` line in the recommendation's risks (an agreement is a `✓` evidence line) |
| `caution` | adds `JEV_DISAGREES` to the recommendation's `cautions[]` (TRADE WITH CAUTION) |
| `approval` | also turns an AUTO execution into an approval (the policy decision becomes REQUIRE_APPROVAL with rule `jev-signal-check`; the approval's rationale carries the check); AUTO waits at most 3 s for a running check and goes ahead unchanged without an answer |

A gate above `off` is **refused at startup** (the application does not start) unless the check is enabled and
`CalibrationService.passes("signal-check", <set version>)` holds (docs/calibration.md); a runtime change goes through
the same rule. Hejje has no config reload, so a change of gate is a restart.

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

## Passive entries (plan M9.8)

A definition with `entry_order: { type: limit_touch, ... }` (docs/strategy-dsl.md), or a bot's `ENTER_*` decision with
`"entryOrder": { "type": "limit_touch", "maxRequotes": 3, "cancelAfterSeconds": 90, "maxChaseBps": 10 }`
(docs/bots.md), enters with a resting limit instead of a market order:

- **Placement.** The entry is a LIMIT at the touch from the quote cache: the best bid for a long, the best ask for a
  short, rounded to the tick away from a fill (bid down, ask up); without a bid/ask, the last price. The position is
  sized from that limit to the stop. Risk is evaluated once, on this intent. The instruments of a `limit_touch`
  deployment stream in FULL mode so the touch is known.
- **Re-quotes.** `PassiveEntries` watches the working order on every tick and every second. When the touch moves away
  (the bid above a long's limit, the ask below a short's), the order is **modified** to the new touch through the
  execution engine (rate limiter, executor lease), audited `ENTRY_REQUOTED`. At most `max_requotes` times; a new limit
  never goes beyond the signal's reference price ± `max_chase_bps` (capped there, then it waits). Quantity is never
  changed.
- **Giving up.** After `cancel_after_seconds`, or when the touch moves away again with the re-quotes spent, the order
  is cancelled, the signal ends `EXPIRED` with the note `ENTRY_NOT_FILLED: <why>`, the pending position is dropped
  (`ENTRY_FAILED`), and `ENTRY_NOT_FILLED` is audited.
- **Partial fill.** Once part of the order has filled, the rest is cancelled and the position opens with the filled
  quantity; the protective stop is placed for that quantity (a cancelled entry with fills counts as filled).
- **Fills in SIM and PAPER.** The paper broker fills a resting limit when a later tick trades at or through its price
  (a replayed M1 bar's four ticks can therefore fill it inside the bar).
- The slippage report counts passive entries apart (docs/analytics.md).
