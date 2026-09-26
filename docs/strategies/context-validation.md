# Daily context validation (plan Phase 8, M8.8)

Do the Phase 8 numbers predict anything? Ratings, the market condition, bases and historical analogs are shown as
**context** from the day they exist, but none of them may change a score, a decision or a universe until the claim it
rests on has passed the test written here. This page is the protocol. It is committed **before the first run**; the
results below the protocol are written by `research/tools/context_validation.py` and committed afterwards, so the git
history shows protocol first, results second. A `FAIL` is a valid outcome and stays on this page.

## Protocol (pre-registered 2026-09-20, before any validation run)

**No tuning on the evaluation range.** Every threshold of the engines comes from the O'Neil literature (distribution
days, follow-through days, base depths, the 5 % buy zone, 7 % stop) or was fixed when the engine was built in M8.2–M8.6
(`config/ratings.yaml`, `config/analogs.yaml`, `config/regime.yaml`), before any outcome had been looked at. If a
threshold is changed after a result here has been seen, the engine version is bumped and the hypothesis needs a **new
pre-registration on new data**; it is never re-tested on this range.

**Evaluation range.** The 24 months ending at the latest session with data, pinned with `--latest <date>` at the
first run and recorded in the results. Forward-looking measurements stop early enough for their outcome to be realised
inside the range.

**Survivorship bias (applies to H1, H3, H5).** The NIFTY 500 file holds today's constituents only
(`docs/data.md`). Stocks that were delisted or dropped from the index are missing from every historical date, which
flatters every unconditional return on this universe. The hypotheses below therefore compare groups **within** the
same biased universe (bullish-tagged against bearish-tagged, restricted against unrestricted) rather than reading an
absolute return, and the evaluation uses recent data where the bias is smallest. It is still stated next to every
daily-universe result.

**Dependence.** Observations from the same date are correlated (one market day moves every stock). Tests on pooled
observations therefore cluster by date: the statistic is computed per date first and the t-value is taken over dates.

### H1. Daily analogs lean the right way

- **Claim.** A stock whose daily-analog direction is `BULLISH*` has a higher forward return than one tagged `BEARISH*`.
- **Sample.** Every 5th session of the range (so that the forward windows of consecutive samples barely overlap and to
  bound compute), all universe symbols, lookbacks **15 and 30**, forward windows **5 and 10** sessions; the last
  sample date is 10 sessions before `--latest`. The direction tag used is the one of the forward window being measured.
- **Measure.** Realised forward return = close `f` sessions later / close on the sample date − 1 (closes from
  `daily_rating`). Per sample date: mean return of `BULLISH*` minus mean return of `BEARISH*` (dates with at least 5
  observations in each group). Reported as well: each group against the **unconditional base rate** (all observations
  of that date), and the same by reliability bucket.
- **Pass mark**, for each of the four (lookback, forward) cells, on observations with reliability `MEDIUM` or `HIGH`:
  the mean per-date spread is **≥ 0.40 %** for 5 sessions and **≥ 0.60 %** for 10 sessions, with a date-clustered
  **t ≥ 2.0**, at least **300 observations in each group**, and a positive spread in **≥ 60 %** of the calendar
  quarters that have at least 30 observations per group. **H1 passes when at least three of the four cells pass**
  (four comparisons: one may pass or fail by chance). `INCONCLUSIVE` when fewer than three cells reach the observation
  minimum.

### H2. Session analogs lean the right way

- **Claim.** At a checkpoint, an instrument tagged `BULLISH*` does better from the checkpoint to 15:10 than one tagged
  `BEARISH*`.
- **Sample.** Every 5th session of the range, the NIFTY 50 constituents with M5 history, the four checkpoints
  (09:45, 10:15, 11:15, 13:00).
- **Measure.** Realised return = close of the bar that ends at 15:10 / close of the last bar that ended by the
  checkpoint − 1. Per date and checkpoint: mean of `BULLISH*` minus mean of `BEARISH*` (at least 3 observations each).
- **Pass mark.** Pooled over the four checkpoints: mean spread **≥ 0.10 %**, date-clustered **t ≥ 2.0**, at least
  **200 observations in each group**. The per-checkpoint table is informational (four more comparisons would invite a
  chance pass). `INCONCLUSIVE` below the observation minimum.

### H3. Relative strength selects better stocks for the intraday strategies

- **Claim.** A stock strategy does better when, each session, it may only enter the 10 NIFTY 50 stocks with the highest
  RS (long strategies) or the 10 with the lowest (short strategies), ranked on the **previous session's** `rsRaw`.
- **Sample.** The stock-universe strategies that passed the Phase 6 bake-off (`docs/strategies/bakeoff.md`), named on
  the command line. Index and futures strategies have nothing to select and are skipped. A strategy with
  `direction: both` is skipped as well (the filter needs one side).
- **Measure.** Two backtests per strategy over the evaluation range with identical settings (`splits: NONE`,
  `NEXT_OPEN`, 10 bps slippage, current costs): unrestricted, and restricted with the research-only `sessionFilter`
  (a session without a previous-session snapshot blocks entries). Compared: net expectancy in R, trades, profit factor.
- **Pass mark** per strategy: restricted expectancy − unrestricted expectancy **≥ +0.05 R**, restricted expectancy
  **> 0**, restricted trades **≥ 30**. **H3 passes when it holds for a majority of the tested strategies** (for the
  only one when one was tested). `INCONCLUSIVE` when no strategy reaches 30 restricted trades or none was eligible.

### H4. The market condition is a useful gate for longs

- **Claim.** Long entries do better when they are allowed only on sessions whose **previous session's** market
  condition was `CONFIRMED_UPTREND` or `UPTREND_UNDER_PRESSURE`.
- **Sample.** The same bake-off survivors with `direction: long` (any universe). Short strategies are not gated.
- **Measure.** Two backtests per strategy as in H3: ungated, and gated with a `sessionFilter` that allows `BUY` only on
  permitted sessions. A session whose previous label is `UNKNOWN`, `RALLY_ATTEMPT` or `DOWNTREND`, or missing, blocks
  longs.
- **Pass mark** per strategy: gated expectancy − ungated expectancy **≥ +0.05 R**, gated expectancy **> 0**, gated
  trades **≥ 30**. **H4 passes on a majority of the tested strategies.** `INCONCLUSIVE` as in H3. Reported as well: the
  share of sessions the gate blocked (a gate that blocks almost everything, or almost nothing, is not informative).

### H5. Bases have positive expectancy (informational)

- **Claim.** The M8.4 trade plan (buy at the pivot, stop 7 % below, goal 20 % above; reversal setups: stop at the
  session low, goal 8 %) has positive expectancy on this universe.
- **Sample.** Every setup of the past-setups ledger that **closed inside the evaluation range**.
- **Measure.** Per base type: triggered setups, hit-goal / stopped / expired counts, mean and median outcome in R; the
  same with and without `volumeConfirmed`, and by the market condition on the trigger date. R is before costs and
  assumes fills at the pivot (or the open on a gap): optimistic, and survivorship bias applies.
- **Mark.** `PASS` when at least one base type has **≥ 50 triggered setups** with a **mean outcome ≥ +0.25 R**, and its
  volume-confirmed subset does at least as well as the unconfirmed one. Nothing in Hejje trades these setups: H5 only
  decides whether a swing phase (CNC, overnight; plan Phase 9 candidate) is worth planning. `INCONCLUSIVE` with fewer
  than 50 triggered setups in total.

### What a verdict allows (plan M8.9)

| Verdict | Consequence |
|---|---|
| H1 or H2 `PASS` | the bounded `HistoricalContextAdjuster` (−5..+5) and the Today context items may be built |
| H3 `PASS` | the `leaders:nifty50:<N>` universe alias may be built |
| H4 `PASS` | the DSL filter `market_condition in [...]` may be built |
| H5 `PASS` | a swing phase may be planned; nothing is built from it in Phase 8 |
| `FAIL` or `INCONCLUSIVE` | stays display-only, labelled "not validated" on the stock, screener and Today pages |

### Procedure

```bash
export HEJJE_URL=https://hejje.malgudi.app HEJJE_API_KEY=...     # admin, market:read, strategies:read, strategies:write
python3 research/tools/context_validation.py plan --latest 2026-09-18 [slugs…]      # ranges, sample dates, request counts; no writes
python3 research/tools/context_validation.py run  --latest 2026-09-18 [slugs…]      # computes what is missing, measures, saves research/out/context-validation.json
python3 research/tools/context_validation.py report                                  # writes the tables and verdicts below
```

`run` is resumable (stored ratings and summaries are skipped by the server) and takes `--only h1,h2,h3,h4,h5`. H2 asks
for its sample dates in ascending order, so the server reads the session history once and extends it date by date
(`docs/analogs.md`); a candle backfill during the run, or midnight, costs one more full read. Before
it: the M8.1 backfill, `POST /ratings/compute`, `POST /ratings/bases/compute` and the regime relabelling (classifier
version 2) over at least the evaluation range plus its warm-up.

## Results

<!-- context-validation:results:start -->
Not run yet. The protocol above was committed first.
<!-- context-validation:results:end -->

## Findings

<!-- context-validation:findings:start -->
To be written after the run: which hypotheses passed, which failed and why, and what M8.9 builds as a consequence.
<!-- context-validation:findings:end -->
