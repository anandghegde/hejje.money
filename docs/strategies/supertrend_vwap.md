# supertrend_vwap — Supertrend flip with VWAP confirmation (long)

**Rationale.** The Supertrend (ATR bands around the bar midpoint that ratchet with price) is one of the most used
retail trend indicators in India. A close crossing above the line marks a flip to an uptrend; requiring the close to be
above VWAP filters flips that happen while the session is still net-sold. The trade is held until the line flips back.

**Rules in words.** On the NIFTY and BANKNIFTY front-month futures, 5-minute bars, between 09:30 and 14:30: buy when
the close crosses above Supertrend(10, 3) and is above VWAP. Exit early when the close crosses back below the
Supertrend. Stop 1.5 × ATR(14); no target; flat by 15:10; one trade a day; ₹2,000 at risk. No entries within 15 minutes
of a high-risk event.

**Parameters.** Supertrend(10, 3) (Wilder ATR, continuous across sessions) · ATR stop 1.5 × ATR(14) · target none ·
exit rule on the flip back · window 09:30–14:30 · max 1/day (pre-registered, M6.3).

**Source.** Common retail trend setup; Supertrend by Olivier Seban, (10, 3) is the default in most charting platforms.

**Target none and the risk pipeline.** As `stock_in_play_orb`: the `minRewardRisk` risk control passes an intent with
no target, so no substitute target was needed.

**Regime fit.** Trending sessions preferred; ranging sessions avoided (whipsaw flips); high volatility neutral.

**Known weaknesses.** In ranging sessions flips come in pairs and each costs a full round trip; the line is continuous
across sessions, so an overnight gap can flip it at the open, which the 09:30 window start only partly filters.

**Baseline.** Pending: the M6.5 bake-off on the continuous NIFTY and BANKNIFTY futures.
