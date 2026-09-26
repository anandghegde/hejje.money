# Historical analogs: methodology (plan M8.5, M8.6)

"Has the market looked like this before, and what happened next?" For a symbol and a lookback, Hejje finds the past
windows anywhere in the universe that resembled the current one, and reports the distribution of what followed them.
Two engines share the same distances, scores, outcome statistics and tags:

- **Daily analogs**: D1 windows of 5–50 sessions over the NIFTY 500 (`config/universe/nifty500.yaml`), forward windows
  of 3, 5, 10 and 15 sessions. Computed every evening for every symbol and lookback.
- **Session analogs**: today's session so far, at 09:45, 10:15, 11:15 and 13:00, against every past session of the
  intraday universe (NIFTY 50 constituents, the two indices, continuous futures) at the same time of day; the forward
  window is checkpoint → 15:10, the strategies' force-exit time.

Module `money.hejje.analogs`, off by default (`hejje.analogs.enabled`); weights, bands and thresholds in
`config/analogs.yaml`. This is **evidence, not a forecast**, and until the validation in
[`strategies/context-validation.md`](strategies/context-validation.md) passes it changes no score, decision or universe.

## Rules that hold everywhere

- **No look-ahead, by construction.** A window's features are a function of its own sessions (and the 50 before it, for
  volume). A daily window is a candidate for benchmark date `D` only when its lookback **and** the longest forward window
  after it end before `D`. A session is a candidate only when its date is before the benchmark's date. The scaling
  statistics are taken over exactly those candidates. The API never serves a daily summary whose session has not closed
  on the Hejje clock (the simulation clock in SIM).
- **Immutable.** A summary is keyed `(date, instrument, kind, lookback, checkpoint, engine version)` and written once.
  Changing a weight, band or threshold means bumping `hejje.analogs.engine-version`.
- **Sample size before percentage.** Every rate carries its count in the API, on every screen and in every sentence.
  Below `min-evidence` (10) matches the direction is `INSUFFICIENT`.
- **Templated prose.** The five-sentence read is built from the fields with hedged wording; no LLM.
- **Reproducible.** The same candles and engine version give the same summaries; compute jobs return a SHA-256 over them.

## Window features (daily)

On the window's closes `c[1..L]` (log closes `y`):

| Feature | Definition |
|---|---|
| path | `y` z-normalised (mean 0, standard deviation 1) |
| volatility | standard deviation of the `L−1` log returns |
| trend | least-squares slope of `y` on the session index × `L` (the fitted drift over the window) |
| range position | `(y[L] − min y) / (max y − min y)`, 0..1 (0.5 for a flat window) |
| volume z | the window's mean volume against the 50 sessions before it, in their standard deviations, clipped to ±5 |
| max drawdown | the deepest close-to-close drawdown inside the window, as a positive fraction |

A window needs `L + 50` sessions of history; a younger listing has no summary for that lookback.

## Distance components and the similarity score

Seven components per candidate, in Konseki's names:

| Component | Definition |
|---|---|
| `path_correlation` | Pearson correlation `r` of the two z-normalised paths |
| `shape_distance` | RMS Euclidean distance of the z-paths, `sqrt(2·(1 − r))` (0 identical, 1.41 uncorrelated) |
| `volatility_distance`, `trend_distance`, `range_position_distance`, `volume_distance`, `risk_distance` | absolute difference of the scalar features |

`similarity_score = 0.30·shape + 0.20·(1 − r) + 0.12·vol/σ + 0.12·trend/σ + 0.10·rangePos/σ + 0.08·volume/σ + 0.08·risk/σ`
where each `σ` is the standard deviation of that feature over all eligible candidates of the benchmark date and
lookback (the "universe-wide" scale). Lower is closer. A candidate above `max-distance` (1.5) is no match.

**Search.** Per lookback a table of the scalar features of every window is built once (five floats per window; the
engine otherwise holds only one close and one volume array per symbol). A candidate's path is compared only when its
volatility, trend and range position are each within `prefilter` (1.0) universe standard deviations of the
benchmark's. The best `max-matches` (50) are kept after **de-overlapping**: one match per symbol per `2 × lookback`
span, best first; the benchmark symbol's own windows are excluded for `lookback + 15` sessions before the benchmark.
Measured on synthetic data of production size (500 symbols × 2,500 sessions): about 60 ms per symbol and lookback on
one core, so a night's 500 × 8 is a few minutes on four threads (`nightly-budget` 30 min; over budget: cut lookbacks
before optimising).

## Match quality

Six component scores 1–5 (`shape`, `trend`, `volatility`, `rangePosition`, `volume`, `risk`), each a piecewise-linear
map of its distance, clamped outside the knots:

| Score | 5 | 4 | 3 | 2 | 1 |
|---|---|---|---|---|---|
| shape: RMS path distance | ≤ 0.30 | 0.45 | 0.65 | 0.85 | ≥ 1.10 |
| the others: distance / σ | ≤ 0.25 | 0.5 | 1.0 | 1.5 | ≥ 2.0 |

A match's `quality` is the mean of the six. The summary's `qualityTag` comes from the median match quality: `STRONG`
≥ 4, `MODERATE` ≥ 3, else `WEAK` (`NONE` without matches).

## Outcomes

Per forward window over the match set, in percent: `count`, `winRate` (share of matches that closed higher), `mean`,
`median`, `p25`/`p75` (linear interpolation), `best`, `worst`, the step-by-step `avgPath` with its `p25Path`/`p75Path`
band, MAE and MFE (the worst and best cumulative close inside the window per match: `maeMedian`, `maeP25` = the worse
quartile, `mfeMedian`, `mfeP75`), `distinctSymbols`, `distinctYears`.

## Tags

Percent thresholds scale with `√f` for a forward window of `f` sessions (session analogs: `percent-scale`).

| Tag | Values | Rule |
|---|---|---|
| `direction` | `BULLISH_STRONG`, `BULLISH`, `MIXED`, `BEARISH`, `BEARISH_STRONG`, `INSUFFICIENT` | win rate **and** median together: median > 0 with win rate ≥ 0.55 (`BULLISH`) or ≥ 0.65 (`STRONG`); the mirror for bearish (≤ 0.45 / ≤ 0.35 with median < 0); otherwise `MIXED`; under 10 matches `INSUFFICIENT` |
| `consistency` | `TIGHT`, `NORMAL`, `WIDE` | IQR ≤ 0.8·√f % / ≥ 1.6·√f % |
| `reliability` | `HIGH`, `MEDIUM`, `LOW`, `INSUFFICIENT` | `HIGH`: ≥ 30 matches from ≥ 15 symbols across ≥ 4 years; `MEDIUM`: ≥ 15, ≥ 8, ≥ 2 |
| `risk` | `HIGH`, `MODERATE`, `LOW` | median MAE ≤ −1.0·√f % / ≤ −0.5·√f % |
| `outlier` | true / false | dropping the two matches farthest from the median moves the mean by more than 30 % of itself (and by ≥ 0.1 %) |

## Splits

The same matches in two groups, each with its count, win rate and median. Daily: **seasonality**, matches that ended in
the benchmark date's calendar month against the other months. Session: **same weekday** against other weekdays, and
**expiry day** against the rest (from the event calendar; without expiry events for old dates that group is simply small).

`context` lists the benchmark's own scalar features across all eight lookbacks (session: at the checkpoint).

## The templated read

Five sentences (`AnalogNarrative`): distribution, risk, reliability, match quality, takeaway. Example: *"In 40 similar
past 15-session windows, price ended higher over the next 5 sessions 27 times (68 %); the median move was +1.24 % and half of the
outcomes fell between −0.80 % and +2.10 %."* Careful variants replace the plain ones for `INSUFFICIENT` evidence, `WEAK`
quality, `HIGH` risk, an `outlier`-driven mean and `WIDE` outcomes. The takeaway always ends "This is historical
context, not a forecast."

## Session analogs

A session is reduced to its M5 closes as **returns on the previous close**, preceded by the opening gap, so the gap is
part of the shape; the z-normalised path up to the checkpoint is compared like a daily path. The scalar slots hold, in
multiples of the daily ATR(14) (a simple 14-session mean of the true range, from M5 bars): range so far (volatility
slot), return so far from the previous close (trend), position in the 15-minute opening range, clipped to −2..3
(range position), log of cumulative volume over the 20-session same-time average, clipped to ±2 (volume), and the
deepest drawdown so far (risk). A **context distance** (weight 0.10) adds the previous session's return in ATRs and the
previous close's position in the 20-session daily range. There is no prefilter and no de-overlap: about 50,000
candidate sessions are compared by brute force in milliseconds.

The outcome is the path from the checkpoint's close to the bar that closes at 15:10, in percent (`closeAtr` per match
in ATR multiples, `session.medianReturnAtr` for the set). `session` also reports, with counts, how many matches' high
and low **at the checkpoint held** to 15:10, and the median time of the matched sessions' high and low.

A candidate session needs a previous close, 14 earlier sessions and every M5 bar up to 15:10; today needs every bar up
to the checkpoint. A summary is a function of stored bars only (today's up to the checkpoint, the universe's before
today), so it does not matter when it is computed: a one-minute job computes each checkpoint once it has passed for the
instruments of the enabled deployments and the watchlist (on simulation time in SIM), and the API computes any other
symbol on demand. `SessionAnalogSimIT` shows a SIM replay giving what the stored day gives. The reduced history
(about 1 kB per past session) is cached for the last date asked. A later date **extends** it with the sessions in
between rather than reading every session again, so a run over many dates in ascending order (the H2 validation, a
backfill) reads the history once; a session only depends on the ones before it, so the result is the same as a fresh
read (`SessionAnalogHistoryCacheTest`). It is read from scratch instead for an earlier date, after any write to the
Parquet store (backfill, import, continuous series), when the universe changed, and on the first request of each Hejje
day (overnight the Postgres candles are pruned and the event calendar may have changed). An engine-version change needs
a restart, which empties the cache.

## Storage and retention

`analog_summary` (one JSON document per key, kept forever) and `analog_match` (the matches of a key as one document),
pruned after `match-retention-days` (30) at 04:15 IST. The validation of plan M8.8 reads summaries only.

## Known limitations

- **Survivorship bias** (daily): candidates come from today's index members only; stocks that were delisted or dropped
  out are missing, which flatters forward returns. Validation therefore measures recent data and states the caveat.
- Closes only: MAE/MFE are close-based (intraday extremes inside a daily forward window are not seen).
- Matches from the same market episode are correlated; `distinctYears` and `reliability` are the guard, not a cure.
- No indices, futures or cross-exchange symbols in the daily universe; no DTW, learned embeddings or approximate
  nearest-neighbour index.
