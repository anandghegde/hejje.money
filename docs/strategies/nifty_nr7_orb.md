# nifty_nr7_orb — NIFTY opening range breakout after an NR7 day (long)

**Rationale.** Toby Crabel observed that a day with the narrowest range of the last seven (NR7) tends to be followed
by range expansion. Taking the `nifty_orb` breakout only on the session after an NR7 day should keep the breakouts
most likely to run and skip the rest.

**Rules in words.** As [`nifty_orb`](nifty_orb.md) (NIFTY front-month future, 5-minute bars, 09:30–12:00: close above
the 15-minute opening-range high, above VWAP, relative volume above 1.2), and only when yesterday's range was the
narrowest of the last seven sessions. Stop at the opening-range low; target 2R; flat by 15:10; one trade a day; ₹2,000
at risk.

**Parameters.** `prev_day_nr(7) == 1` plus the `nifty_orb` parameters (pre-registered, M6.3).

**Source.** Toby Crabel, *Day Trading with Short Term Price Patterns and Opening Range Breakout* (1990), NR7.

**Regime fit.** Trending sessions preferred; ranging sessions avoided; high volatility neutral (an NR7 day in a
volatile regime is rarer).

**Known weaknesses.** NR7 days are roughly one session in seven, so the trade count is low (the bake-off needs 30
out-of-sample trades); the session ranges come from the intraday bars fed to the context, so a missing bar in the
previous session changes the flag; ties count as narrowest.

**Baseline.** Pending: the M6.5 bake-off on the continuous NIFTY future.
