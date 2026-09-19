# nifty_intraday_momentum_long — first half hour predicts the last (long)

**Rationale.** Gao, Han, Li and Zhou (2018) show that the market's return over the first half hour (previous close to
10:00 in the US) predicts the return over the last half hour, attributed to late-informed and hedging flows. This is
the long leg for the NIFTY future: when the first half hour of the NSE session is up, buy for the close.

**Rules in words.** On the NIFTY front-month future, 5-minute bars, on the bars closing at 14:45 or 14:50: buy when
the return from the previous close to the close of the bar ending at 09:45 is positive. Stop 2 × ATR(14); no target;
flat at 15:10 (the NSE session's last half hour ends at 15:30, but MIS positions must be flat before the broker's 15:20
square-off); one trade a day; ₹2,000 at risk. No entries within 15 minutes of a high-risk event.

**Parameters.** `opening_return(30m) > 0` · ATR stop 2 × ATR(14) · target none · window 14:45–14:50 · max 1/day
(pre-registered, M6.3).

**Source.** L. Gao, Y. Han, S. Z. Li, G. Zhou, "Market intraday momentum", *Journal of Financial Economics* 129 (2018).

**Known deviations.** The paper's last half hour is 15:30–16:00 US time and it holds to the close; here the holding
period is 14:45–15:10 (25 minutes), cut short by the MIS force exit. The paper uses the index/ETF; this trades the
future, whose previous close is the future's.

**Regime fit.** Trending sessions preferred; ranging and volatile neutral (the paper finds the effect stronger on
volatile days and on news days).

**Known weaknesses.** A single short holding window makes costs large relative to the expected move; every session
with a positive first half hour trades, so there is no quality filter; expiry-day flows dominate the close on
Thursdays.

**Baseline.** Pending: the M6.5 bake-off. The rule uses price only, so `INDEX:NIFTY 50` is an acceptable proxy when the
futures history is shorter than two years (the series used is stated in the results).
