# nifty_orb_breakdown — NIFTY 15-minute opening range breakdown (short)

**Rationale.** The mirror of `nifty_orb`: a close below the opening range with price under VWAP and volume marks
sellers in control; index futures allow shorting without borrow.

**Rules in words.** On the NIFTY front-month future, 5-minute bars, between 09:30 and 12:00: sell when a bar closes
below the 09:15–09:30 low, below the session VWAP, with volume at least 1.2× the slot's usual volume. Stop at the
opening-range high; target 2× the risk; flat by 15:10; at most one trade a day; ₹2,000 at risk per trade.

**Parameters.** range 15m · relative volume 1.2 (20 sessions) · target 2R · window 09:30–12:00 · max 1/day.

**Regime fit.** Trending (down) sessions preferred; ranging avoided; blocks near high-risk events.

**Known weaknesses.** Indian index dips are bought aggressively in bull regimes (short squeezes back through VWAP);
gap-down opens that fill; the same wide-range problem as the long version.

**Baseline.** Pending (see `docs/strategies/README.md`).
