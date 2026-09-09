# vwap_reversion — mean reversion back to VWAP (long)

**Rationale.** Intraday extensions of more than ~0.3 % below VWAP with RSI oversold are often liquidity-driven and
revert to VWAP within an hour or two in ranging sessions.

**Rules in words.** On NIFTY and BANKNIFTY front-month futures, 5-minute bars, between 10:00 and 14:30, after the
first 45 minutes: buy the first up-close when the close is more than 0.3 % below VWAP and RSI(14) is below 30. Stop
0.5 % below the entry; the target is VWAP itself (exit when price touches the current VWAP); give up after 120 minutes;
flat by 15:10; at most two trades a day; ₹2,000 at risk.

**Parameters.** extension 0.3 % · RSI 14 < 30 · stop 0.5 % · max holding 120 min · window 10:00–14:30 · max 2/day.

**Regime fit.** Ranging preferred; trending avoided (catching a falling knife on trend-down days). Blocks within
30 minutes of a high-risk event.

**Known weaknesses.** Trend days keep extending; the VWAP target shrinks as price falls (VWAP follows); percentage
stops ignore volatility.

**Baseline.** Pending (see `docs/strategies/README.md`).
