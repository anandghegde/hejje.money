# open_high_short — "Open = High" (short)

**Rationale.** The mirror image of `open_low_long`: a stock whose open is still the session high after 15 minutes has
met sellers at the open. Traded short when it is also below VWAP and below the previous close.

**Rules in words.** On the NIFTY 50 constituents, 5-minute bars, on the bars closing at 09:30 or 09:35: short when
today's high so far equals today's open, the close is below VWAP and below the previous day's close. Stop 0.75 % above
the entry; target 2× the risk; flat by 15:10; at most one trade a day per stock; ₹1,500 at risk per trade. No entries
within 15 minutes of a high-risk event.

**Parameters.** stop 0.75 % · target 2R · window 09:30–09:35 · max 1/day (pre-registered, M6.3).

**Source.** "Open = High" Indian practitioner setup, mirror of Open = Low.

**Regime fit.** Trending and gap-down sessions preferred; ranging sessions avoided.

**Known weaknesses.** As `open_low_long`; in addition intraday shorts in cash equities are MIS only and short squeezes
around news are sharp.

**Baseline.** Pending: the M6.5 bake-off on NIFTY 50 constituents.
