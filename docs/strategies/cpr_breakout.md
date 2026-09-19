# cpr_breakout — narrow central pivot range breakout (long)

**Rationale.** The central pivot range (CPR) is built from the previous session's high, low and close. A very narrow
CPR means yesterday closed near the middle of its range and the market is balanced; Indian practitioners read it as a
tell for a trending day, when price leaves the narrow band and does not come back. The entry waits for the close to
leave the band upward with price above VWAP, so volume-weighted buyers are in control.

**Rules in words.** On the NIFTY and BANKNIFTY front-month futures, 5-minute bars, between 09:30 and 13:00: buy when
today's CPR is narrower than 0.2 % of the pivot, the close crosses above the top of the CPR, and the close is above
the session VWAP. Stop 1.5 × ATR(14) below the entry; target 2× the risk; flat by 15:10; at most one trade a day;
₹2,000 at risk per trade. No entries within 15 minutes of a high-risk event.

**Parameters.** CPR width < 0.2 % · ATR stop 1.5 × ATR(14) · target 2R · window 09:30–13:00 · max 1/day
(pre-registered in `plan/phase-6-strategy-research.md`, M6.3).

**Source.** Indian practitioner setup (Frank Ochoa's "Secrets of a Pivot Boss" popularised the CPR and its width as a
trend-day filter); the 0.2 % threshold is the common rule of thumb for the index.

**Regime fit.** Trending sessions preferred (the setup is a trend-day tell); ranging sessions avoided (price oscillates
through a narrow band and produces repeated crosses); high volatility neutral.

**Known weaknesses.** A narrow CPR day can also open with a gap far from the band, so the cross never happens or
happens late; only one side is traded (a mirror definition would be needed for breakdowns); `vwap` needs volume, so
the setup cannot use the index as a proxy for missing futures history.

**Baseline.** Pending: the M6.5 bake-off (`docs/strategies/bakeoff.md`) on the continuous NIFTY and BANKNIFTY futures.
