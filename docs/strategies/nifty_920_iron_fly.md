# nifty_920_iron_fly — the 9:20 straddle as a defined-risk iron fly (options, neutral, PAPER-only)

**Rationale.** Selling the ATM straddle at 09:20 is among the most automated retail strategies in India: it collects
the day's time decay and the post-open volatility crush, and loses on a trend day. The risk engine refuses naked short
options (`optionsDefinedRisk`), so the short straddle is protected with long wings 200 points out of the money: an iron
fly with a known maximum loss.

**Rules in words.** On NIFTY, 5-minute bars, on the bar closing at 09:20 (or 09:25): buy the nearest-expiry call and
put 200 points out of the money (hedge legs, filled first), then sell the ATM call and the ATM put, one lot each. Exits,
whichever comes first: a short leg's premium rises 30 % above its entry premium; the combined P&L of the four legs
reaches −₹3,000; the NIFTY future moves 150 points either way from its price at the signal (the neutral band); 15:10.
One position a day. No entries within 15 minutes of a high-risk event.

**Parameters.** entry 09:20 · wings ±200 points · short-leg stop 30 % · combined stop ₹3,000 · underlying band ±150
points · force exit 15:10 · max 1/day (pre-registered, plan M6.4).

**How it differs from the common variant.** Most retail implementations put a 30 % stop on each short leg and close only
that leg, keeping the other side open. Hejje manages an options position as one unit, so **any leg stop closes the
whole position** (shorts bought back first, then the wings sold). On expiry day `expiry: nearest` is the expiring
contract (it stays selectable until `hejje.options.expiry-day-cutoff`, 13:00, long after 09:20), which is the variant
most traders run and the one with the sharpest gamma risk.

**Direction neutral.** The strategy takes no side on the underlying (`direction: neutral`, docs/strategy-dsl.md): the
legs name `ce`/`pe`, and the `points` stop becomes a band of ±150 around the signal price that closes the position
(`UNDERLYING_BAND`) when left either way.

**Evaluation.** The historical store has no option candles, so it cannot be backtested. It is judged on forward paper
trades only: deployed in PAPER at autonomy 4, where the `auto_strategy` policy waives the Hejje Score for options
strategies in PAPER (simulated fills), so a position opens every eligible day without a confirmation.

**Regime fit.** Quiet and ranging sessions are the edge; trend days and event days (RBI, budget, Fed) are the losses.
No regime preferences are set so the paper record covers every kind of day.

**Known weaknesses.** Gap-and-trend days hit the band or a leg stop early; slippage on four market orders at the open is
material; the combined stop and leg stops are software exits checked every `hejje.options.monitor-interval` (5 s), not
exchange stop orders.
