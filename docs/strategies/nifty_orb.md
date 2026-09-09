# nifty_orb — NIFTY 15-minute opening range breakout (long)

**Rationale.** The first 15 minutes of the NSE session absorb overnight news; a close above that range on volume,
with price above VWAP, tends to start a trend day in the direction of the breakout. A classic index day-trading setup.

**Rules in words.** On the NIFTY front-month future, 5-minute bars, between 09:30 and 12:00: buy when a bar closes
above the 09:15–09:30 high, above the session VWAP, with volume at least 1.2× the usual volume for that time slot.
Stop at the opening-range low; target 2× the risk; flat by 15:10; at most one trade a day; ₹2,000 at risk per trade.

**Parameters.** range 15m · relative volume 1.2 (20 sessions) · target 2R · window 09:30–12:00 · max 1/day.

**Regime fit.** Trending sessions preferred; ranging sessions avoided (false breakouts back into the range); high
volatility neutral (bigger range = wider stop = smaller size). Blocks entries within 15 minutes of a high-risk event.

**Known weaknesses.** Gap-and-fade opens produce a breakout that reverses; expiry days and event days (RBI, Fed,
budget) distort the opening range; a wide range (>0.8 % of the index) makes 2R targets rarely reachable intraday.

**Baseline.** Pending: run `POST /backtests` with `FIXED 60/20/20` and `WALK_FORWARD 6/2` over the backfilled
continuous NIFTY series and record trades, expectancy (R), profit factor, max drawdown (R) and warnings here.
