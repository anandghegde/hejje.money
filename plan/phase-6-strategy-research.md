# Phase 6 — Strategy Research (bake-off)

Goal of the phase: find out which intraday setups actually carry an edge on our own data, net of costs, before any of
them trades real money. Phase 2 shipped six price/volume strategies whose baseline backtests were never run (the VM
backfill is a carried-forward follow-up). This phase runs those baselines, adds the most widely traded intraday setups
that the DSL cannot yet express, and compares everything under one pre-registered protocol. Sentiment and news are out
of scope: every new rule uses price, volume and time only.

Rules that apply to every milestone here:

- **Parameters are pre-registered.** Each new definition uses the conventional textbook or practitioner values written
  in this file, committed before any backtest of it runs (docs/strategies/README.md rule). Changing a parameter after
  seeing results goes through an experiment (M4.7), which carries the multiple-comparisons note.
- **One protocol for all.** Same data range, split, fill model, slippage and cost model for every strategy (M6.5).
- **A final holdout stays untouched.** The last 6 months of data are excluded from every run until M6.5 step 4.

Milestones M6.1 → M6.3 → M6.5 are ordered; M6.4 is independent and can run in parallel.

---

## M6.1 Historical data audit and baseline backtests  (size: M, mostly ops on the VM)

**Tasks.**
1. On the VM, run the backfill from `docs/data.md` if it has not been run: index M1/M5/D1, NIFTY and BANKNIFTY contracts,
   continuous futures, NIFTY 50 constituents M5/D1. Record per series the first and last session and the
   `GET /market/history/integrity` result in a new table in `docs/data.md` ("Coverage as of <date>").
2. Note the continuous-futures start date. Contracts that expired before the first instrument sync cannot be backfilled
   (docs/data.md), so futures history may be much shorter than index history. When it is shorter than 2 years, a
   strategy whose rules use only price may be backtested on `INDEX:NIFTY 50` as a proxy; rules that use `vwap` or
   `relative_volume` cannot (index candles carry no volume). State the series used in every result.
3. Verify `config/costs.yaml` against the current Zerodha and NSE charge sheets and clear the `verify: true` flags
   (brokerage, STT, exchange charges, stamp duty for equity intraday and futures). Results before this step do not count.
4. Label the regime history (`POST /context/regime/label`) so every backtest has a by-regime breakdown.
5. Run the baseline backtest of the six backtestable bundled strategies under the M6.5 protocol and fill in the
   "baseline results" section of each `docs/strategies/<slug>.md`.

**Verification.** Coverage table committed; six baseline backtests `DONE` with `resultHash` recorded; costs flags cleared.

---

## M6.2 Indicators for the new setups  (size: M)

**Tasks.** Add to `IndicatorFactory`, `IndicatorCatalog` and `RuleWords`, document in `docs/indicators.md` and
`docs/strategy-dsl.md`, with golden rows from `research/tools/gen_indicator_fixtures.py` (pandas reference where TA-Lib
has no equivalent):

| Call | Definition |
|---|---|
| `session_open`, `session_high`, `session_low` | today's first-bar open; running high / low of today's bars so far |
| `pivot`, `cpr_top`, `cpr_bottom`, `cpr_width_pct` | from the previous session's H, L, C: `P = (H+L+C)/3`, `BC = (H+L)/2`, `TC = 2P − BC`; top/bottom = max/min(TC, BC); width = (top − bottom) / P × 100 |
| `supertrend(n, k)` | the standard Supertrend line: bands `hl2 ± k × atr(n)` (Wilder), final-band recursion, line flips on a close through the band; continuous across sessions like `ema` |
| `prev_day_nr(n)` | 1 when the previous session's range (H − L) is the smallest of the last `n` sessions' ranges, else 0 (NR7 = `prev_day_nr(7) == 1`); from daily candles |
| `opening_return(d)` | (close of the bar ending at 09:15 + d − `prev_day_close`) / `prev_day_close` × 100; `NOT_READY` before that bar, constant for the rest of the session |

**Verification.** `./gradlew test --tests 'money.hejje.market.indicators.*' --tests '*IndicatorCatalog*'`; golden
fixtures regenerated; each new call validates in the DSL and shows in evidence lists with its observed value.

---

## M6.3 New directional strategies  (size: M)

**Tasks.** Add each as `strategies/<slug>.yaml` plus `docs/strategies/<slug>.md` (rationale, rules in words, source of
the setup, expected regime fit, known weaknesses) and a row in `docs/strategies/README.md`. `direction: both` is not
supported by the backtester, so two-sided setups are two definitions. Defaults unless stated: `timeframe: 5m`,
`force_exit_time: "15:10"`, `max_trades_per_day: 1`, `event_rules` block at 15 minutes, risk-based sizing at the
library's `risk_rupees`.

| Slug | Universe | Entry (all) | Stop / target / window | Source |
|---|---|---|---|---|
| `cpr_breakout` | NIFTY, BANKNIFTY futures | `cpr_width_pct < 0.2`, `close crosses_above cpr_top`, `close > vwap` | `atr_multiple 1.5` / `risk_multiple 2` / 09:30–13:00 | narrow CPR as a trend-day tell (Indian practitioner setup) |
| `open_low_long` | NIFTY 50 constituents | `session_low == session_open`, `close > vwap`, `close > prev_day_close` | `percent 0.75` / `risk_multiple 2` / 09:30–09:35 | "Open = Low" (Indian practitioner setup) |
| `open_high_short` | NIFTY 50 constituents | `session_high == session_open`, `close < vwap`, `close < prev_day_close` | `percent 0.75` / `risk_multiple 2` / 09:30–09:35 | mirror of the above |
| `nifty_nr7_orb` | NIFTY future | `nifty_orb` entry plus `prev_day_nr(7) == 1` | as `nifty_orb` | Crabel, narrow-range days before breakouts |
| `stock_in_play_orb` | NIFTY 50 constituents | `gap_pct > 1`, `close > opening_range_high(5m)`, `relative_volume(20) > 2`, `close > vwap` | `atr_multiple 1.5` / target `none` (held to force exit) / 09:20–10:30 | Zarattini, Barbon & Aziz (2024), 5-minute ORB on stocks in play |
| `nifty_intraday_momentum_long` | NIFTY future | `opening_return(30m) > 0` | `atr_multiple 2` / target `none` / 14:45–14:50 | Gao, Han, Li & Zhou (2018), first half hour predicts the last |
| `nifty_intraday_momentum_short` | NIFTY future | `opening_return(30m) < 0` | as above, short | as above |
| `supertrend_vwap` | NIFTY, BANKNIFTY futures | `close crosses_above supertrend(10, 3)`, `close > vwap`; exit rule `close crosses_below supertrend(10, 3)` | `atr_multiple 1.5` / target `none` / 09:30–14:30 | common retail trend setup |

Known deviations to state in the docs: `stock_in_play_orb` cannot rank stocks cross-sectionally (the paper trades the
top 20 by relative volume), so it filters on thresholds and relies on the deployment's daily budget to cap the number of
trades; the constituent list is today's (survivorship bias over three years). Check how the risk pipeline treats
`target: none` against `min_reward_risk`; if it hard-blocks, use `risk_multiple 3` and say so.

**Verification.** All eight load at startup as `DRAFT` and validate; a fixture-data test per new strategy produces the
hand-computed signal bars (`./gradlew test --tests '*BundledStrategies*'` or the existing library test).

---

## M6.4 9:20 iron fly (options, PAPER-only)  (size: M)

The time-based short straddle entered at 09:20 is among the most automated retail strategies in India. The historical
store has no option candles (docs/options.md), so it cannot be backtested; it is judged on forward paper trades only.
The defined-risk variant (iron fly) is used because the risk engine refuses naked short options.

**Tasks.**
1. DSL: `direction: neutral`, allowed only with `family: options`. Legs must name `option: ce | pe` (no
   `directional`/`opposite`). The `stop` (`points`, `percent` or `atr_multiple`) becomes a symmetric band around the
   underlying's price at the signal: the position closes when the underlying leaves the band either way.
2. Signal engine and options monitor accept neutral signals (no side on the underlying, band check in the monitor).
3. Definition `nifty_920_iron_fly`: universe NIFTY; entry `session_minutes >= 5`; window 09:20–09:25; legs buy CE
   `offset 200` and buy PE `offset 200` (both `hedge_first`), sell CE `atm` and sell PE `atm`, `stop_pct 30` on each
   short leg; `combined_exit.stop_rupees 3000`; stop `points 150`; `force_exit_time "15:10"`. Document that a leg stop
   closes the whole position (Hejje exits the position as one), unlike the common per-leg variant, and that on expiry
   day `nearest` is the expiring contract.
4. Deploy in PAPER at autonomy 4 (rehearsal with simulated fills) so it collects trades without manual confirmation.

**Verification.** DSL validation tests (neutral only with options; `directional` legs refused); options monitor test
closing on the band in both directions; `OptionsIT` extended with a neutral four-leg position (hedges filled first).

---

## M6.5 Bake-off run and report  (size: M)

**Protocol** (written into `docs/strategies/bakeoff.md` before the first run):

- Range: the longest span every series in a strategy's universe covers, ending 6 months before the latest session.
- Split: `WALK_FORWARD {trainMonths: 12, testMonths: 3}`; `NEXT_OPEN` fills; `slippageBps` 5 for futures, 10 for
  stocks; costs from the verified `config/costs.yaml`; default capital and risk per trade.
- A strategy **passes** when, net of costs: out-of-sample trades ≥ 30; out-of-sample expectancy ≥ 0.10R; profit factor
  ≥ 1.2; expectancy positive in at least 60 % of walk-forward test windows; expectancy still > 0 at 2× slippage (the
  score's sensitivity run). About 14 strategies are compared, so roughly one may pass by chance; the holdout exists
  for that reason.

**Tasks.**
1. `research/tools/bakeoff.py` (standard library only; `HEJJE_URL` and `HEJJE_API_KEY` from the environment): for a
   list of strategy slugs, start the backtests under the protocol, poll until done, pull metrics, the Hejje Score and
   the by-regime breakdown, and write the results table into `docs/strategies/bakeoff.md`.
2. Run it on the VM for the 6 bundled and 8 new backtestable strategies.
3. Write the findings: which pass, which regimes each works in, and which fail and why (costs, too few trades,
   unstable windows). No parameter changes in this step.
4. Run each passing strategy once on the 6-month holdout. Only those with positive holdout expectancy advance.
5. Deploy the survivors in PAPER at autonomy 3; the drift monitor (M5.1) compares their paper trades against the
   backtest. Nothing goes LIVE in this phase.

**Verification.** `bakeoff.md` contains the protocol, the full results table (including failures), the holdout results
and the list of PAPER deployments; every number is reproducible from its backtest id and `resultHash`.

---

## Phase 6 exit checklist

- [ ] Data coverage table and verified cost rates committed (M6.1)
- [ ] Baseline results filled in for the six Phase 2 strategies (M6.1)
- [ ] New indicators with golden tests (M6.2)
- [ ] Eight new strategies loaded as DRAFT with docs (M6.3)
- [ ] Iron fly collecting paper trades (M6.4)
- [ ] Bake-off report with protocol, results, holdout and PAPER deployments (M6.5)
