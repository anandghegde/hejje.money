# nifty_intraday_momentum_short — first half hour predicts the last (short)

**Rationale.** The short leg of [`nifty_intraday_momentum_long`](nifty_intraday_momentum_long.md): when the first half
hour of the session is down, short the NIFTY future for the close (Gao, Han, Li and Zhou, 2018).

**Rules in words.** On the NIFTY front-month future, 5-minute bars, on the bars closing at 14:45 or 14:50: short when
the return from the previous close to the close of the bar ending at 09:45 is negative. Stop 2 × ATR(14); no target;
flat at 15:10; one trade a day; ₹2,000 at risk. No entries within 15 minutes of a high-risk event.

**Parameters.** `opening_return(30m) < 0` · ATR stop 2 × ATR(14) · target none · window 14:45–14:50 · max 1/day
(pre-registered, M6.3). Two definitions because the backtester does not support `direction: both`.

**Source.** L. Gao, Y. Han, S. Z. Li, G. Zhou, "Market intraday momentum", *Journal of Financial Economics* 129 (2018).

**Regime fit, deviations and weaknesses.** As the long leg.

**Baseline.** Pending: the M6.5 bake-off (price only, so the index proxy applies as for the long leg).
