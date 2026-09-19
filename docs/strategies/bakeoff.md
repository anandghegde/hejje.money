# Strategy bake-off (plan Phase 6, M6.5)

Which intraday setups carry an edge on our own data, net of costs, before any of them trades real money. Every
backtestable bundled strategy (the six Phase 2 price/volume strategies and the eight Phase 6 ones) is compared under
**one protocol**, written here before the first run and not changed afterwards. Options strategies (the two M5.4
spreads/calls and the M6.4 iron fly) cannot be backtested and are judged on forward paper trades only.

## Protocol (pre-registered 2026-09-19, before any bake-off run)

**Parameters.** Every definition runs with the parameters committed in `strategies/*.yaml` (conventional textbook or
practitioner values, `plan/phase-6-strategy-research.md`). A parameter changed after a result has been seen goes
through an experiment (M4.7) with its multiple-comparisons note and is not part of this bake-off.

**Data.**

- Series: NIFTY/BANKNIFTY universes trade the continuous futures (`NFO:<UNDERLYING>:FUT:CONT`, docs/data.md); stock
  universes the NIFTY 50 constituents' 5-minute candles. When a continuous future covers less than two years and the
  strategy's rules use price only (no `vwap`, no `relative_volume`), it runs on the index as a proxy
  (`INDEX:NIFTY 50`, `INDEX:NIFTY BANK`); a rule that needs volume cannot use the index and runs on the shorter futures
  history. The series used is stated in every result row.
- Range: the longest span every series in the strategy's universe covers (the latest first session to the earliest
  last session of their 5-minute coverage), **ending 6 months before the latest session**.
- **Holdout:** the last 6 months (the day after the range end to the latest session) are excluded from every run until
  step 4 below, then used once per passing strategy.

**Backtest settings.** `splits: WALK_FORWARD {trainMonths: 12, testMonths: 3, anchored: false}`; `fillModel: NEXT_OPEN`;
`slippageBps` 5 for futures and index series, 10 for stocks; costs from `config/costs.yaml` as verified on 2026-09-19
(M6.1; results before the verification do not count), today's rates applied to the whole range (the April 2026 F&O
STT increase therefore also charges older trades: conservative); default capital (₹10,00,000) and each definition's
`risk_rupees`.

**Pass rule.** A strategy passes when, net of costs, all of these hold:

| Criterion | Threshold | Source |
|---|---|---|
| Out-of-sample trades | ≥ 30 | `bySplit.OUT_OF_SAMPLE.totalTrades` |
| Out-of-sample expectancy | ≥ 0.10R | `bySplit.OUT_OF_SAMPLE.expectancyR` (R net of costs) |
| Out-of-sample profit factor | ≥ 1.2 (no losing trade counts as a pass) | `bySplit.OUT_OF_SAMPLE.profitFactor` |
| Walk-forward stability | expectancy > 0 in ≥ 60 % of the test windows that have at least one trade | `windows[]` |
| Slippage robustness | expectancy still > 0 at 2× slippage | the Hejje Score's slippage-sensitivity run (`doubledSlippageExpectancyR`), which re-runs the backtest with twice the slippage |

**Multiple comparisons.** About 14 strategies are compared, so at a 5 % false-positive rate roughly one may pass by
chance. That is what the holdout is for: only a passing strategy with **positive expectancy on the untouched 6-month
holdout** advances, and even then only to PAPER.

**Procedure.**

1. `python3 research/tools/bakeoff.py plan <slugs…>` — print each strategy's series, range and holdout; record the
   latest session used.
2. `python3 research/tools/bakeoff.py run --latest <date> <slugs…>` — start every protocol backtest, poll to the end,
   recompute the Hejje Score, pull the by-regime breakdown (trend × volatility), apply the pass rule and write the
   results table below.
3. Write the findings: which pass, which regimes each works in, which fail and why (costs, too few trades, unstable
   windows). **No parameter changes in this step.**
4. `python3 research/tools/bakeoff.py holdout --latest <same date> <passing slugs…>` — one run each on the holdout
   (split `NONE`, same fills, slippage and costs). Only positive holdout expectancy advances.
5. Deploy the survivors in PAPER at autonomy 3; the drift monitor (M5.1) compares their paper trades with the
   backtest. Nothing goes LIVE in this phase.

Every number below is reproducible from its backtest id and `resultHash` (docs/backtesting.md).

## Strategies compared

| Group | Slugs |
|---|---|
| Phase 2 baselines (6) | `nifty_orb`, `nifty_orb_breakdown`, `vwap_trend_continuation`, `vwap_reversion`, `pdh_pdl_breakout`, `ema_pullback` |
| Phase 6 additions (8) | `cpr_breakout`, `open_low_long`, `open_high_short`, `nifty_nr7_orb`, `stock_in_play_orb`, `nifty_intraday_momentum_long`, `nifty_intraday_momentum_short`, `supertrend_vwap` |
| Forward paper only | `nifty_920_iron_fly` (M6.4), `nifty_orb_call_buy`, `nifty_bull_call_spread` (M5.4) |

## Results

Pending the M6.1 data audit on the VM (backfill, coverage table, verified costs, regime labels).

<!-- bakeoff:results:start -->
Not run yet.
<!-- bakeoff:results:end -->

## Findings

Pending the results.

## Holdout

<!-- bakeoff:holdout:start -->
Not run yet.
<!-- bakeoff:holdout:end -->

## PAPER deployments

| Strategy | Version | Deployment | Mode / autonomy | Since | Why |
|---|---|---|---|---|---|
| `nifty_920_iron_fly` | pending | pending | PAPER / 4 | pending | forward paper record (M6.4); not backtestable |
