# Phase 11 — Swing trading (CNC, overnight, broker-side stops)

Goal of the phase: hold the Phase 8 base setups for days instead of closing everything by 15:10. Swing positions are
delivery (CNC), long only (Indian cash equity allows no overnight shorts), protected overnight by a stop that lives
at the broker (a Zerodha GTT), sized by an overnight risk budget, and run in PAPER first.

Decisions taken with the user (2026-09-26):

| Question | Decision |
|---|---|
| What is traded | The M8.4 trade plans (base breakout at the pivot: stop 7 % below, goal 20 % above; reversal setups: stop at the session low, goal 8 %), not general DSL strategies. A daily DSL mode is out of scope |
| Gate | Built now; nothing leaves PAPER unless the pre-registered H5 validation (docs/strategies/context-validation.md) is `PASS`. With H5 `FAIL` the phase stops at PAPER and says so in PROGRESS |
| Overnight stops | Broker GTT stop-loss per position (fires even when Hejje is down); Hejje trails it tighten-only and reconciles it daily. PAPER simulates GTTs against real prices |
| Mode | PAPER first for every milestone; LIVE needs a separate, explicit decision recorded in PROGRESS after M11.7 |

Binding rules for this phase (on top of plan/README §4):

- **Intraday is untouched.** MIS positions, the 15:10 force exit, intraday risk limits and every existing strategy
  behave exactly as before. Swing is a separate book: its own capital, limits, kill semantics and P&L horizon.
- **Every open swing position has a live stop at the broker.** A position without a confirmed GTT is an incident:
  audited, notified, and new swing entries are blocked until it is fixed. Hejje never removes a GTT without closing or
  replacing the position's protection in the same operation.
- **Stops only tighten.** Trailing moves a GTT trigger up (long), never down; widening needs a manual action, audited.
- **Gaps are priced in.** Risk per position is the stop distance plus a gap allowance, not the stop distance alone;
  backtests fill a stop that the open gapped through at the open, not at the stop.
- **Selling holdings needs authorisation at the broker.** Zerodha requires DDPI (or a per-day eDIS/TPIN
  authorisation) for a GTT or an order to sell delivery holdings. LIVE swing is refused unless the broker reports the
  account can sell holdings without a daily TPIN; this is checked at deploy time and daily.

New tables start at V52 (V51 is the last in use). New module: `money.hejje.swing` (the swing book, its limits and
jobs); GTT support goes into `broker` (adapter API) and `execution` (placing, trailing, reconciling).

Order: M11.1 → M11.2 → M11.3 → M11.4 → M11.5 → M11.6 → M11.7. M11.5 (backtest) can run in parallel with M11.2–M11.4.

---

## M11.1 CNC positions that carry overnight  (size: M)

**Goal.** A delivery position survives the session close, the restart and the next day, and its P&L is reported on a
multi-day horizon.

**Tasks.**
1. Swing book: positions opened with `Product.CNC` by a swing deployment (or manually with `--product CNC`) are
   excluded from the force exit and from intraday limits; they are listed separately (`GET /swing/positions`: entry
   date, days held, entry, stop, goal, R, unrealized P&L).
2. Holdings reconciliation: a daily job (before the open and after the close) compares the swing book with the
   broker's holdings (T+1 settlement: yesterday's buys appear as T1 holdings) and raises a reconciliation issue on any
   difference. PAPER keeps simulated holdings with the same shape.
3. Delivery costs already exist (`config/costs.yaml`, `EQUITY_DELIVERY`); round trips across days use them, including
   DP charges on the sell day (add if missing).
4. Analytics: a `horizon` dimension (`INTRADAY | SWING`) on trades, reviews and the performance report; swing trades
   report holding days and R.

**Acceptance.** A PAPER CNC position opened on day 1 is still open after a restart on day 2, is not force-exited,
reconciles against simulated holdings, and closes on day 3 with delivery costs and a 2-day holding period.

**Verification.** `SwingPositionIT` over three simulated days on the MutableClock.

---

## M11.2 GTT stops at the broker  (size: L)

**Goal.** Every swing position is protected by a broker-side stop that Hejje places, trails and reconciles.

**Tasks.**
1. `BrokerAdapter` gains GTT operations (place, modify, cancel, list; single-trigger and two-leg OCO for stop +
   goal), implemented for Zerodha with the Kite Connect GTT API (verify the current API and limits against Kite's docs
   and record them in `docs/`, as `docs/jev.md` did); Dhan gets its equivalent (Forever orders) or refuses cleanly.
2. PAPER/SIM simulation: GTTs held by the paper adapter trigger on live ticks, and at the open when the open gaps
   through the trigger (filled at the open with slippage).
3. Execution: after a swing entry fills, place an OCO GTT (stop, goal) within seconds; on a partial fill size it to
   the filled quantity; trailing (optional per deployment: move the stop to breakeven at +1R, then trail under the
   20-day low) modifies it tighten-only; closing a position cancels its GTT in the same operation.
4. Reconciliation: at startup, before the open and after the close, list GTTs at the broker and match them to open
   swing positions (missing, orphaned, wrong quantity or trigger → issue + notification; orphans are never deleted
   automatically).
5. Audit: `GTT_PLACED`, `GTT_MODIFIED`, `GTT_TRIGGERED`, `GTT_CANCELLED`, `GTT_MISSING`.

**Acceptance.** Contract tests for the GTT operations on the fake and paper adapters; a gap-down through the stop
fills at the open; a position whose GTT disappears at the broker is flagged and blocks new swing entries.

**Verification.** `BrokerAdapterContractTest` (GTT cases), `SwingGttIT`.

---

## M11.3 Overnight risk  (size: M)

**Goal.** The swing book cannot lose more overnight than the user has decided to risk.

**Tasks.**
1. `swing` risk limits (V52+, editable like `risk_limits`): swing capital, max open positions, max risk per position
   (stop distance + gap allowance, default gap 3 %), max total overnight risk, max positions per industry (from the
   universe's `industry` map), no new entries on the day before RBI policy / Budget / index rebalance (from
   `config/events.yaml`), and no entry in a stock under ASM/GSM surveillance (Phase 8 follow-up flags).
2. Sizing: quantity from the risk budget and the gap-adjusted risk; the order is rejected with a named reason when
   any limit is hit.
3. Kill switch: the existing kill switch stops new swing entries and leaves swing positions with their GTTs; a
   separate "close swing book" action (typed confirmation) exits all swing positions.
4. Dashboard: overnight risk used vs budget on the Risk page and `hejje risk`.

**Acceptance.** Each limit has a test that rejects the entry that would breach it; the kill switch leaves GTTs in
place; the risk dashboard shows the gap-adjusted total.

**Verification.** `SwingRiskTest`, `SwingRiskIT`.

---

## M11.4 Swing entries from the base setups  (size: L)

**Goal.** Setups that trigger during the session become swing entries in PAPER, through the normal approval path.

**Tasks.**
1. A swing deployment (strategy family `swing`, one per universe) turns M8.4 trade plans whose status is `READY`
   into watched setups; the instruments are subscribed during the session.
2. Trigger: price crosses the pivot with volume pace ≥ the plan's threshold (projected session volume vs the 50-day
   average) and not more than 5 % above the pivot (the buy zone); entry by LIMIT at the touch with a small cap, never
   above the buy zone. Reversal setups use their own trigger from M8.4.
3. Signals go through the existing signal → approval → execution path (autonomy levels as for intraday; AUTO only in
   PAPER in this phase); the signal carries the plan's stop and goal, which become the GTT.
4. Time exit: a position that has made neither goal nor stop after `max-holding-days` (default 30) is closed at the
   next open (configurable), and a weekly review lists positions below entry after 10 days.

**Acceptance.** A replayed session in SIM with a READY plan crossing its pivot on volume produces a signal, a PAPER
CNC fill and an OCO GTT; a cross without volume or above the buy zone does not.

**Verification.** `SwingEntriesSimIT`.

---

## M11.5 Multi-day backtests  (size: M)

**Goal.** Swing rules can be tested on history with the same fills as live.

**Tasks.**
1. Backtest mode `SWING` on D1 candles: entries at the trigger price when the day's range crosses it (with the volume
   condition on the day's volume), stops and goals checked on the daily range, gap-through fills at the open, the
   intra-bar order ambiguity resolved against the trade (stop first), delivery costs, holding across days, time exit.
2. The H5 ledger (M8.8) and the `SWING` backtest must agree on the same setups within costs; a parity test on a
   fixture universe proves it.
3. Walk-forward and regime breakdowns as for intraday backtests; a `swing` section in `docs/backtesting.md`.

**Acceptance.** Parity with the H5 ledger on the fixture; a gap-through-stop fixture fills at the open.

**Verification.** `SwingBacktestTest`, `SwingParityTest`.

---

## M11.6 Surfaces and notifications  (size: M)

**Goal.** The swing book is visible and manageable from the web, the TUI and notifications.

**Tasks.** Web: a Swing page (positions with days held, R, stop/goal, GTT status; setups watched today; overnight
risk), on the Phase 10 components with a phone layout. TUI: `hejje swing` (positions, GTTs) and `hejje swing close
<symbol>`. Notifications: entry filled, GTT placed/missing, stop or goal triggered (including gap-through), time exit
due. Agent tools: read-only swing book and risk.

**Acceptance.** Every notification type has a test; the page and the TUI show the same numbers as the API.

**Verification.** web unit tests + Playwright spec `swing.spec.ts`; TUI goldens.

---

## M11.7 PAPER run and the decision  (size: S, mostly a run)

**Goal.** Enough PAPER evidence to decide whether swing goes LIVE.

**Tasks.** Run the swing deployment in PAPER for at least 3 months or 30 closed trades, whichever is later; compare
realized expectancy (net of delivery costs, with gap fills) with the `SWING` backtest and the H5 result; record the
report and the decision (LIVE with which limits, or stop) in PROGRESS.

**Exit checklist.**
- [ ] H5 verdict recorded; with `FAIL`, swing stays PAPER-only and the phase ends here.
- [ ] Every open swing position had a confirmed broker (or simulated) GTT for the whole run; zero unprotected nights.
- [ ] Overnight risk never exceeded its budget; each limit has a test.
- [ ] `SWING` backtest parity with the H5 ledger on the fixture; PAPER results within the backtest's expected range
      or the difference explained.
- [ ] Intraday behaviour unchanged (full suite green; force exit, limits and existing strategies untouched).
- [ ] LIVE decision recorded with the DDPI/eDIS check passing on the account.

**Out of scope for the phase.** Shorting (futures) for swing; margin trading (MTF); options on swing positions;
general DSL strategies on daily bars; fundamentals.
