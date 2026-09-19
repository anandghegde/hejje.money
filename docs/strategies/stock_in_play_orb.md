# stock_in_play_orb — 5-minute opening range breakout on stocks in play (long)

**Rationale.** Zarattini, Barbon and Aziz (2024) find that a 5-minute opening range breakout is profitable on "stocks in
play" (stocks with unusually high opening volume, often after news or a gap) and not on the market at large. This is
the long side of that idea on NIFTY 50 stocks, with a gap filter standing in for news.

**Rules in words.** On the NIFTY 50 constituents, 5-minute bars, between 09:20 and 10:30: buy when the stock opened
more than 1 % above the previous close, the close is above the first 5-minute bar's high, the bar's volume is more
than twice the usual volume for that time slot, and the close is above VWAP. Stop 1.5 × ATR(14) below the entry; no
target (held to the 15:10 force exit); at most one trade a day per stock; ₹1,500 at risk. No entries within 15 minutes
of a high-risk event.

**Parameters.** gap > 1 % · 5-minute range · relative volume(20) > 2 · ATR stop 1.5 × ATR(14) · target none ·
window 09:20–10:30 · max 1/day (pre-registered, M6.3).

**Source.** C. Zarattini, A. Barbon, A. Aziz, "A Profitable Day Trading Strategy For The U.S. Equity Market" (SSRN
4729284, 2024).

**Known deviations from the paper.** The paper ranks all stocks by opening relative volume and trades the top 20 each
day; the DSL cannot rank cross-sectionally, so this definition filters on thresholds instead and relies on the
deployment's daily budget to cap the number of trades. The paper's relative volume is the first 5-minute bar against
its 14-day average; here it is the signal bar against the 20-session average of its slot. The paper trades both
sides; this is long only (`direction: both` is not supported by the backtester). The constituent list is today's, so a
multi-year backtest carries survivorship bias.

**Target none and the risk pipeline.** With `target: none` the order intent carries no target price, and the risk
engine's `minRewardRisk` control passes ("no target/stop") rather than blocking, so no `risk_multiple 3` substitute
was needed (checked in `RiskControls.minRewardRisk`).

**Regime fit.** Trending and gap-up sessions preferred; ranging sessions avoided.

**Known weaknesses.** Gap-and-fade opens; holding to 15:10 gives back open profits on reversal days; high costs
relative to the move on low-priced stocks.

**Baseline.** Pending: the M6.5 bake-off on NIFTY 50 constituents.
