# vwap_trend_continuation — pullback to VWAP in an intraday uptrend (long)

**Rationale.** In a trending session institutions defend VWAP; the first up-close after a touch of VWAP, with the
short EMAs still pointing up, offers a low-risk continuation entry with a stop below the pullback.

**Rules in words.** On NIFTY and BANKNIFTY front-month futures, 5-minute bars, between 10:00 and 14:00, after the
first 45 minutes: buy when the 9 EMA is above the 21 EMA, the previous bar's low touched or crossed VWAP, and the
current bar closes back above VWAP and above the previous close. Stop 1.5 ATR(14) below the entry; target 2R; the stop
moves to breakeven once the trade is 1R in profit; flat by 15:10; at most two trades a day; ₹2,000 at risk.

**Parameters.** EMA 9/21 · ATR 14 × 1.5 · target 2R · breakeven at 1R · window 10:00–14:00 · max 2/day.

**Regime fit.** Trending preferred; ranging avoided (VWAP touches then are mean-reversion, not continuation).
Caution (not block) near high-risk events.

**Known weaknesses.** Choppy sessions produce repeated VWAP crosses; ATR stops are wide early in volatile sessions;
the breakeven rule can cut a good trade on a retest.

**Baseline.** Pending (see `docs/strategies/README.md`).
