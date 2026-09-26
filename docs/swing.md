# Swing trading (Phase 11)

Swing positions are delivery (`CNC`), long only, held for days and protected overnight by a stop that lives at the
broker. The swing book is separate from the intraday book: its own limits, kill semantics and P&L horizon. **PAPER
only**: nothing leaves PAPER until the pre-registered H5 validation (`docs/strategies/context-validation.md`) passes and
a LIVE decision is recorded in `plan/PROGRESS.md`.

## The swing book (M11.1)

- **What is swing.** Every delivery (`product: CNC`) position is part of the swing book, whether it was opened by an
  order with `--product CNC` or (later, M11.4) by a swing deployment. Nothing else uses `CNC`.
- **Separate from intraday.** The intraday risk snapshot (daily loss, open positions, trades per day, loss streak,
  re-entry cooldown, …) leaves `CNC` positions and fills out, and a `CNC` intent skips those intraday limits: it is
  checked for the kill switch, quantity, notional and margin, plus the swing limits (M11.3). Nothing force-exits a
  delivery position: the 15:10 force exit belongs to intraday strategy deployments and the broker's MIS square-off.
- **`swing_position`.** A delivery position that goes long opens a swing round trip (entry date, entry price, the entry
  order's stop and goal); fills update its quantity and average; its return to zero closes it with the exit price and
  the sessions held.
- **`GET /api/v1/swing/positions`** lists the open book with the entry date, sessions held, entry, stop, goal, R
  ((last − entry) / (entry − initial stop)) and unrealized P&L. `GET /api/v1/swing/positions/closed` lists closed round
  trips.

### Holdings and T+1 settlement

At the broker a delivery buy is a `CNC` position on its session; from the next session it is a holding, reported as
`t1_quantity` on the first session after the buy and as settled `quantity` after that. Selling a holding shows as a
negative `CNC` position that day while the holding still lists the shares until the sale settles. `BrokerHolding`
carries both quantities (`quantity`, `t1Quantity`; `totalQuantity()` is their sum).

The fake and the paper adapters simulate this with the same shape (`SimulatedHoldings`): a delivery fill is recorded
for its session, and the holdings view is computed per session. The paper adapter keeps this book in the `paper_state`
table (PAPER mode only), so it survives a restart; the fake keeps it in memory, and a SIM session starts empty.

### Reconciliation

The intraday position reconciliation (every 30 s in session) compares only non-delivery positions. The swing book is
reconciled against the broker's delivery side by `ReconciliationService.reconcileHoldings()`: per scrip, Hejje's `CNC`
quantity must equal the broker's holdings (settled + T1) plus today's `CNC` position. Only scrips the swing book holds,
closed within the last 7 days, or traded for delivery today are compared, so the rest of a real account's holdings is
left alone. A difference is a `HOLDINGS_MISMATCH` reconciliation issue of severity `WARN` (it does not trip the intraday
kill switch); a match resolves it. It runs at startup (executor bootstrap), before the open (`hejje.swing.before-open-cron`,
09:00 IST) and after the close (`hejje.swing.after-close-cron`, 15:45 IST), and on demand with `POST /api/v1/swing/reconcile`.

### Costs and horizon

Delivery round trips use the `EQUITY_DELIVERY` rates (no brokerage, STT on both sides, stamp duty on the buy) and the
depository (DP) charge on the sell day: `hejje.costs.dp-charge` (₹15.34 per scrip and day, GST included), once per scrip
and IST day however many sell fills there are (`docs/costs.md`).

Round trips, trade facts and reviews carry a `horizon` (`INTRADAY` | `SWING`); intraday and delivery fills of the same
instrument never share a round trip. Swing reviews and facts report `holdingDays` (sessions held after the entry
session) and R. `GET /analytics/pnl?groupBy=horizon` and the loss attribution's `horizon` dimension split the two books.

## GTT stops at the broker (M11.2)

Every open swing position is protected by a stop that lives at the broker, so it fires even while Hejje is down.

### The broker contract

`BrokerAdapter` has `placeGtt`, `modifyGtt`, `cancelGtt` and `getGtts` (`Gtt.Request`, `Gtt.Snapshot`): a `SINGLE`
GTT (one trigger, one order) or an `OCO` (two ascending triggers: the stop leg and the goal leg; the first met places its
order and ends the GTT). Statuses follow Kite: `ACTIVE`, `TRIGGERED`, `DISABLED`, `EXPIRED`, `CANCELLED`, `REJECTED`,
`DELETED`. A broker without GTTs refuses cleanly with an `INPUT` error and sends nothing (Dhan: its "Forever orders" are
not wired; swing trading needs Zerodha). GTT calls use the general rate bucket, not the order limits.

**Kite Connect GTT API** (verified 2026-09-26 against https://kite.trade/docs/connect/v3/gtt/, https://zerodha.com/tos/gtt/
and the `javakiteconnect` 4.0.1 client):

| Call | Endpoint |
|---|---|
| place | `POST /gtt/triggers` (`type` = `single` \| `two-leg`, `condition` = {exchange, tradingsymbol, instrument_token, trigger_values, last_price}, `orders` = [{exchange, tradingsymbol, transaction_type, quantity, order_type, product, price, market_protection}]) → `trigger_id` |
| list | `GET /gtt/triggers`: the active GTTs and the others of the last 7 days |
| get | `GET /gtt/triggers/:id` |
| modify | `PUT /gtt/triggers/:id` (the whole condition and orders again) |
| delete | `DELETE /gtt/triggers/:id` |

Limits and behaviour: at most **500 pending GTTs** per account; a GTT is valid for **one year** and then expires; the
trigger must be at least **0.25 %** away from the last price (9 paise under ₹50); a triggered leg places a LIMIT order
or a MARKET order with market price protection (Hejje's stop leg is MARKET with automatic protection `-1`, so a gap
through the stop still fills; its goal leg is a LIMIT at the goal); if a LIMIT leg is not filled that day it is
cancelled at the end of the session; OCO is only for existing holdings; GTTs are allowed for CNC (and MTF), not MIS;
Zerodha cancels GTTs on corporate actions (splits, bonuses, …): the reconciliation then reports `GTT_MISSING`. Selling
holdings through a GTT needs DDPI/POA or an eDIS authorisation (plan: LIVE swing is refused without DDPI; M11.7).

### Simulation (PAPER, SIM, fake)

The fake and the paper adapters hold GTTs themselves (`SimulatedGtts`, never sent to a broker). A GTT fires on the first
tick that meets a trigger: a SINGLE stop placed below the last price fires at or below it (above: at or above), an OCO
fires its stop leg at or below the lower trigger and its goal leg at or above the upper one. The fired leg's order is
placed at once against that tick, so a session that opens through the stop fills the stop at the open (with the paper
adapter's slippage), not at the stop. The paper adapter keeps its GTTs in `paper_state` across restarts.

### Placing, sizing, trailing, closing (execution module, `GttService`)

- **After the entry fills** (the `OrderFilledEvent` of a Hejje delivery order; PAPER and SIM only — nothing is placed at
  a real broker before the LIVE decision), the position gets an OCO GTT from the entry's stop and goal (a single stop
  without a goal), for the filled quantity. Later fills (a partial fill completing, an add, a partial exit) resize it.
  An entry without a stop, or a placement the broker refuses, is a `GTT_MISSING` incident.
- **Stops only tighten.** `PUT /api/v1/swing/positions/{id}/stop` moves the stop up; a lower stop needs
  `"widen": true` (a manual action, audited as `GTT_MODIFIED` with `widened: true`). Trailing is optional per position
  (`swing_position.trail`, set by a swing deployment in M11.4): after the close the stop moves to breakeven once the
  close is 1R above the entry, then one tick under the 20-day low (`SwingTrail`), never down.
- **Closing a position cancels its GTT in the same operation** (`ExecutionEngine.closePosition` for CNC): the GTT is
  cancelled, the exit is submitted, and if the exit fails or is rejected the GTT is placed again. A sell that flattens
  the position by other means cancels it too. Hejje never removes a GTT otherwise.
- **A triggered GTT**: the broker's exit order is unknown to Hejje; when it is a delivery sell of an instrument with an
  active GTT that the broker lists as triggered, it is taken over at once (imported like an external order), the GTT is
  marked `TRIGGERED` (`GTT_TRIGGERED`), and the fill closes the position and the swing round trip.

### Reconciliation and incidents

`GttService.reconcile()` runs at startup (executor bootstrap), before the open and after the close (the swing jobs), and
with `POST /api/v1/swing/reconcile`. It lists the broker's GTTs and matches them to the open delivery positions:

| Issue | When | Effect |
|---|---|---|
| `GTT_MISSING` | an open position has no GTT, or the broker no longer lists it as active (deleted, disabled, expired, rejected) | the GTT row turns `MISSING`; audited `GTT_MISSING`; notified (`GTT_MISSING`, critical); **new swing entries are refused** (`swingProtection`) until the position is protected again |
| `GTT_MISMATCH` | the broker's GTT has another quantity or stop | notified (`GTT_MISMATCH`) |
| `GTT_ORPHAN` | an active GTT at the broker protects no open position (at a real broker only GTTs Hejje placed count; the account's own are left alone) | notified; **never deleted automatically** |

All are WARN reconciliation issues: they do not trip the intraday kill switch. Placing the protection again
(`GttService.protect`, which replaces a `MISSING` GTT) resolves `GTT_MISSING`. Audit events: `GTT_PLACED`,
`GTT_MODIFIED`, `GTT_TRIGGERED`, `GTT_CANCELLED`, `GTT_MISSING`.

## Overnight risk (M11.3)

### Limits

`swing_limits` (one row per mode, `GET`/`PUT /api/v1/swing/limits`, audited `SWING_LIMITS_UPDATED`):

| Limit | Default (seed) | Check (named in the rejection) |
|---|---|---|
| — | — | `swingPaperOnly`: a delivery entry outside PAPER/SIM is refused (the phase gate; LIVE needs the M11.7 decision) |
| — | — | `swingProtection`: refused while any open delivery position lacks an active GTT for its quantity (M11.2) |
| — | — | `swingPrice`, `swingStop`: a price to measure the risk (the limit price, the last price or the broker's quote) and a stop below it |
| swing capital | ₹5,00,000 | `swingCapital`: the open positions' cost plus the entry |
| max open positions | 6 | `swingOpenPositions` (a new instrument only) |
| max risk per position | ₹2,500 | `swingRiskPerPosition`: quantity × (stop distance + gap allowance), with the rest of the position when adding |
| gap allowance | 3 % | the part of the price an opening gap can jump the stop by |
| max overnight risk | ₹10,000 | `swingOvernightRisk`: the book's gap-adjusted risk plus the entry's |
| max positions per industry | 2 | `swingIndustry`: from the industry map of `hejje.swing.universe` (`nifty500`); unknown industry passes |
| no entries before events | on | `swingEventNextSession`: an RBI policy, the Budget or an index rebalance on the next session (`config/events.yaml`; the event calendar must be on) |
| no entry under surveillance | on | `swingSurveillance`: the stock's NSE ASM/GSM flag as of today (no lists fetched: not blocking) |

A position's gap-adjusted risk is `quantity × (max(0, price − stop) + gap% × price)` at the last price with the stop in
force (the GTT's, else the entry's); without a known stop it is the whole value. Long only: a delivery sell that does
not close a position is refused (`swingLongOnly`). The intraday limits never see a delivery intent (above).

### Sizing

`POST /api/v1/swing/size {entry, stop}` returns the largest quantity the limits allow: the gap-adjusted risk per share
is `entry − stop + gap% × entry`, and the quantity is the smallest of the risk-per-position budget, what is left of the
overnight budget, and what is left of the swing capital (`limitedBy` names the binding one). The swing entries of M11.4
size with it.

### Kill switch and closing the book

The existing kill switch stops new swing entries (every action sets `stop_new_orders`), but its `CLOSE_ALL_POSITIONS`
closes the intraday book only: swing positions stay open with their GTTs at the broker, which keep protecting them.
`POST /api/v1/swing/close-all` with the typed confirmation `CLOSE SWING BOOK` (and an `Idempotency-Key`) exits every
swing position, each close cancelling its GTT in the same operation (audited `SWING_BOOK_CLOSED`).

### Dashboard

`GET /api/v1/risk` carries `overnightRisk` (the book's gap-adjusted total), `overnightRiskBudget` and `swingPositions`;
`GET /api/v1/swing/risk` has the per-position breakdown with the capital deployed. (The web Risk page and `hejje risk`
do not show them yet: a surfaces follow-up for M11.6.)

## Swing entries from the base setups (M11.4)

### The swing deployment

`POST /api/v1/swing/deployments {universe, autonomyLevel, trail, maxHoldingDays, volumePace, maxChaseBps}` deploys the
swing strategy of a universe in the server's mode: **PAPER or SIM only**, one enabled deployment per universe. Its
backing strategy `swing_<universe>` (family `swing`, product `CNC`, created on first use and moved DRAFT → PAPER) has
no rules of its own and **no intraday runner**: the signal engine skips it, the intraday backtester refuses it (the
SWING backtest judges it, M11.5), and its signals never create a `strategy_position` (there is no intraday stop order
or 15:10 force exit; the GTT protects the delivery position). The deployment's params carry `universe`, `trail`
(default on), `max_holding_days` (30), `volume_pace` (1.4) and `max_chase_bps` (20).

### Watched setups and the trigger

Before the open (and at startup, after a deployment change, and lazily on the first bar of a session) the
`SwingWatcher` loads the session's **READY** setups: the M8.4 bases and reversals whose status as of the previous
session is `FORMING` or `NEAR_PIVOT` (detected, not triggered, not closed), on an instrument of an enabled swing
deployment, with no open swing position. Their instruments are subscribed. `GET /api/v1/swing/setups` lists them with
the watcher's state.

Every closed M1 bar of a watched instrument goes through `SwingTrigger`:

| State | When |
|---|---|
| `WAIT` | the close is below the pivot |
| `ABOVE_BUY_ZONE` | the close is more than 5 % above the pivot (above the plan's `buy_high`): no entry on that bar |
| `STOP_TOO_NEAR` | the stop is less than `hejje.swing.min-stop-distance-pct` (0.25 %) below the price: Kite would refuse the GTT |
| `NO_VOLUME` | a base whose volume pace is below the deployment's `volume_pace`: the session's volume so far projected to a full session (375 minutes) over the 50-session average daily volume (the M8.4 breakout volume); no average → no entry. Reversal setups have no volume condition |
| `TRIGGER` | otherwise: one signal per setup and session |

A trigger creates a signal on the normal path (`SignalGeneratedEvent`: Today, confirmation, or AUTO for an autonomy 4-5
deployment in PAPER/SIM; the Hejje Score does not apply to swing signals, which count as unscored paper rehearsals) with
the plan's stop and goal, and `swing` evidence: `baseId`, `type`, `pivot`, `buyHigh`, `pace`, `avgVolume50` and the entry
`limit` = the trigger price plus `max_chase_bps`, never above the buy zone, on the tick grid. The entry is that LIMIT
(product CNC), sized from the swing limits' gap-adjusted budget (`POST /swing/size`), and checked by the swing limits.
Its fill places the OCO GTT (M11.2); its trailing follows the deployment's `trail` (breakeven at +1R, then a tick under
the 20-day low, never nearer than 0.25 % to the close).

### Time exit and weekly review

A position that has made neither goal nor stop after its deployment's `max_holding_days` sessions (manual positions:
`hejje.swing.max-holding-days`) is closed at the next open (`hejje.swing.time-exit-cron`, 09:15:30; the close cancels
its GTT). `GET /api/v1/swing/time-exits` lists the positions due. The weekly review (`hejje.swing.review-cron`, Friday
15:50; `GET /api/v1/swing/review`) lists the positions held at least `hejje.swing.review-after-days` (10) sessions and
still below their entry.

## Multi-day backtests (M11.5)

`POST /api/v1/swing/backtest` runs the SWING backtest on D1 bars with the live swing rules (or, `rules: LEDGER`, the
H5 ledger's rules, which reproduce the ledger within costs). The rules, the gap-through fills, the report and the
parity test are in `docs/backtesting.md` ("Swing"). The intraday backtester refuses a `swing` strategy.
