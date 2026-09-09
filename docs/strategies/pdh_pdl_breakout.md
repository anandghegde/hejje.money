# pdh_pdl_breakout — previous-day-high breakout with volume (long)

**Rationale.** The previous session's high is the most watched intraday level; the first 5-minute close above it with
above-normal volume and price above VWAP frequently continues as trapped shorts cover and breakout buyers enter.

**Rules in words.** On ten liquid NIFTY 50 stocks, 5-minute bars, between 09:30 and 13:00: buy when a bar closes above
the previous day's high, the previous bar had closed at or below it (a fresh break), volume is at least 1.5× the slot's
usual volume and the close is above VWAP. Stop 1.5 ATR(14) below the entry; target 2R; flat by 15:10; at most one trade
a day per stock; ₹1,500 at risk.

**Parameters.** relative volume 1.5 (20 sessions) · ATR 14 × 1.5 · target 2R · window 09:30–13:00 · max 1/day.

**Regime fit.** Trending and gap-up sessions preferred; ranging avoided. Blocks near high-risk events.

**Known weaknesses.** Breakouts on results days behave differently (earnings gaps); stock-specific news is not
modelled until Phase 3; MIS equity costs (STT on the sell side) eat into small moves.

**Baseline.** Pending (see `docs/strategies/README.md`).
