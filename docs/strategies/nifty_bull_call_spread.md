# nifty_bull_call_spread (bundled, M5.4)

The `nifty_orb` breakout traded as a **bull call spread**: buy the ATM weekly call (placed first, `hedge_first`) and sell
the call 100 points higher (`strike: {offset: 100}`, out of the money for calls). The maximum loss is the net debit.

- Execution: one ALL_OR_NOTHING basket with rollback; the short call is placed only after the long call has filled, so the
  options defined-risk control sees it covered.
- Exits: combined P&L stop ₹2,500 and target ₹4,000, the underlying's opening-range-low stop, 15:10 — the short leg is
  bought back before the long leg is sold.
- Bear put spread: the same legs with `direction: short` (a short signal makes directional legs puts, and the offset
  moves the short put 100 points lower).
- PAPER-only until it has paper history (docs/options.md).
