# open_low_long — "Open = Low" (long)

**Rationale.** A stock whose opening price is still the session low after the first 15 minutes has met buyers at the
open and not been sold below it. Indian intraday traders scan NIFTY 50 stocks at 09:30 for Open = Low and buy the ones
that also trade above VWAP and above the previous close.

**Rules in words.** On the NIFTY 50 constituents, 5-minute bars, on the bars closing at 09:30 or 09:35: buy when
today's low so far equals today's open, the close is above VWAP and above the previous day's close. Stop 0.75 % below
the entry; target 2× the risk; flat by 15:10; at most one trade a day per stock; ₹1,500 at risk per trade. No entries
within 15 minutes of a high-risk event.

**Parameters.** stop 0.75 % · target 2R · window 09:30–09:35 · max 1/day (pre-registered, M6.3).

**Source.** "Open = Low" Indian practitioner setup (widely shared scanner rule).

**Regime fit.** Trending and gap-up sessions preferred; ranging sessions avoided.

**Known weaknesses.** The condition compares exact prices (`==` within 1e-9), so a single tick below the open
invalidates it, which is the intended rule but makes it sensitive to data errors in the first bar; many stocks can
qualify on a strong day and there is no cross-sectional ranking (the deployment's daily budget caps the number of
trades); the constituent list is today's (survivorship bias over a multi-year backtest).

**Baseline.** Pending: the M6.5 bake-off on NIFTY 50 constituents (5-minute equity candles).
