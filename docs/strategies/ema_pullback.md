# ema_pullback — pullback to the 9 EMA in a stacked EMA uptrend (long)

**Rationale.** When the 9, 21 and 50 EMAs are stacked upward the intraday trend is established; a bullish bar that
dips to the 9 EMA and closes above it is a continuation entry with a natural stop under the recent swing low.

**Rules in words.** On ten liquid NIFTY 50 stocks, 5-minute bars, between 09:45 and 14:00: buy when EMA9 > EMA21 >
EMA50, the bar's low touched the 9 EMA, and the bar closed above the 9 EMA and above its open. Stop at the lowest low
of the last 10 bars; target 2R; trail the stop 2 ATR(14) below the close; flat by 15:10; at most two trades a day per
stock; ₹1,500 at risk.

**Parameters.** EMA 9/21/50 · swing lookback 10 · target 2R · trailing ATR 14 × 2 · window 09:45–14:00 · max 2/day.

**Regime fit.** Trending preferred; ranging avoided. Caution near high-risk events.

**Known weaknesses.** Needs 50 bars of warm-up (first usable signal after ~13:25 on a cold start; the engine warms up
from prior sessions); swing stops can be very tight in low-volatility sessions (small R, many stop-outs).

**Baseline.** Pending (see `docs/strategies/README.md`).
