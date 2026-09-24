# Market regime engine (PRD §13, plan M3.1)

The regime module labels every trading session along six dimensions and stores the labels per session and
classifier version. Labels are deterministic functions of stored candles, so the same data and the same
`classifier-version` always give the same labels (the label job returns a hash you can compare across runs).
When an input is missing the dimension is `UNKNOWN`; the engine never throws into callers, and with
`hejje.regime.enabled=false` every dimension is `UNKNOWN` with the reason in the evidence list.

All thresholds live in `config/regime.yaml` (`hejje.regime.*`, defaults identical in code). Bump
`hejje.regime.classifier-version` when you change a rule or a threshold; sessions without a label under the new
version are relabelled on the next start (`label-on-startup`) or with `POST /api/v1/context/regime/label`.

## Inputs

| Input | Source | Used by |
|---|---|---|
| Index daily bars (`INDEX:NIFTY 50`, D1) | market store; a session without a D1 candle gets one built from its M5 bars | trend, volatility (ATR), opening (previous close) |
| INDIA VIX daily closes (D1, plus today's M5 bars) | market store | volatility |
| Index M5 bars of the session | market store (live: the recent Postgres candles) | opening refinement, intraday structure |
| Constituent M5 bars (`config/universe/nifty50.yaml`, `hejje.regime.universe`) | market store | breadth (live path) |
| Constituent D1 closes | market store | breadth (historical labelling: advance/decline only) |
| Event calendar | `EventEnvironmentSource` (`NORMAL` until M3.3) | event environment |

The daily series is walked forward one session at a time (`DailyWalker`), so a session's trend and volatility only
see bars up to and including that session. Percentiles are ranks within the trailing `lookback-sessions` window
(250) and need at least `min-sessions` (60) bars.

## Rules

### Trend (`trend`)

Inputs: close, EMA20, EMA50, EMA20 five sessions back, ADX(14), all on the index daily series.

| Label | Rule |
|---|---|
| `STRONG_UP` | close > EMA20 > EMA50, ADX ≥ `strong-adx` (25) and EMA20 slope over `slope-sessions` ≥ `strong-slope-pct` (+1.0 %) |
| `UP` | close > EMA20 > EMA50 and ADX ≥ `trend-adx` (18) |
| `STRONG_DOWN` | close < EMA20 < EMA50, ADX ≥ 25 and slope ≤ −1.0 % |
| `DOWN` | close < EMA20 < EMA50 and ADX ≥ 18 |
| `RANGE` | anything else (mixed EMA structure, or ADX below 18) |
| `UNKNOWN` | fewer daily bars than the EMA50 / ADX warm-up |

### Volatility (`volatility`)

VIX close percentile and ATR(14)/close percentile over the trailing window, averaged when both exist:

| Combined percentile | Label |
|---|---|
| < 10 | `VERY_LOW` |
| 10 – 30 | `LOW` |
| 30 – 70 | `NORMAL` |
| 70 – 90 | `HIGH` |
| ≥ 90 | `EXTREME` |

`UNKNOWN` when neither percentile has `min-sessions` of history.

### Opening (`opening`)

Gap = (session open − previous close) / previous close. |gap| < `flat-gap-pct` (0.2 %) → `FLAT`, else `GAP_UP` /
`GAP_DOWN`. Once the bar closing at 09:15 + `opening-range-minutes` (09:30) exists, a gap refines to
`GAP_CONTINUATION` when that close is beyond the open in the gap's direction, or `GAP_REJECTION` when it is back
through the open by at least `rejection-fraction` (50 %) of the gap; otherwise the gap label stays. A flat open is
never refined. `UNKNOWN` without a previous close or an open.

### Breadth (`breadth`)

Across the universe: advances vs declines (last close vs previous session close) and, live, the share of
constituents above their session VWAP. The two shares are averaged when both exist:

| Positive share | Label |
|---|---|
| ≥ 75 % | `STRONG_POSITIVE` |
| ≥ 60 % | `POSITIVE` |
| ≤ 25 % | `STRONG_NEGATIVE` |
| ≤ 40 % | `NEGATIVE` |
| otherwise | `MIXED` |

`UNKNOWN` when fewer than `min-coverage` (60 %) of the universe has data. Historical labelling uses daily closes
only, so it has advance/decline but no VWAP share.

### Intraday structure (`intraday-structure`)

From the index M5 bars of the session (progressive during the day, `finalLabel` at the close). Features: day range,
range expansion (day range / opening-range range), close position within the day range, day range / previous
daily ATR, crosses of the session average price (volume-weighted where volume exists; equal-weighted for an index),
and the excursion above/below the open as a share of the day range. Checked in this order:

| Label | Rule |
|---|---|
| `UNKNOWN` | fewer than `min-bars` (6) bars |
| `LOW_VOLATILITY_COMPRESSION` | range / ATR ≤ `compression-max-range-atr` (0.5) |
| `REVERSAL_DAY` | range / ATR ≥ `reversal-min-range-atr` (0.8, or ATR unknown) and the excursion against the close's side of the open is ≥ `reversal-min-excursion` (50 % of the day range) |
| `TREND_DAY` | range expansion ≥ `trend-range-expansion` (2.0), close in the top or bottom `1 − trend-close-position` (20 %) of the range, ≤ `trend-max-vwap-crosses` (2) crosses |
| `HIGH_VOLATILITY_CHOP` | range / ATR ≥ `chop-min-range-atr` (1.0) and ≥ `chop-min-vwap-crosses` (4) crosses |
| `RANGE_DAY` | otherwise |

### Event environment (`event-environment`)

`NORMAL` for every session until the events module (M3.3) registers an `EventEnvironmentSource` bean that maps the
day's events to `EARNINGS_HEAVY | RBI | FED | BUDGET | MACRO_EVENT_SESSION | EXPIRY_SESSION`.

### Market condition (plan M8.3)

The daily market call, a seventh dimension: `CONFIRMED_UPTREND | UPTREND_UNDER_PRESSURE | RALLY_ATTEMPT | DOWNTREND |
UNKNOWN`. It is an end-of-day label: the final label of a session includes that session, an intraday snapshot carries
the previous session's call. Thresholds: `hejje.regime.market-condition.*` in `config/regime.yaml`.

**Index volume proxy.** `INDEX:NIFTY 50` carries no volume, so a session's index volume is the summed D1 turnover
(`close × volume`) of the `nifty50.yaml` constituents (for a session whose D1 candles are not stored yet: last intraday
close × summed intraday volume). With fewer than `min-constituents` (45) constituents the session has no proxy volume:
it can be neither a distribution day nor a follow-through day, and when it is the session being labelled the label is
`UNKNOWN`.

| Rule | Definition |
|---|---|
| Distribution day | index close down ≥ 0.2 % on higher proxy volume than the previous session. It leaves the count after 25 sessions, or once the index closes 5 % above that day's close |
| `CONFIRMED_UPTREND` → `UPTREND_UNDER_PRESSURE` | ≥ 4 distribution days in the count |
| `UPTREND_UNDER_PRESSURE` → `CONFIRMED_UPTREND` | the count falls to ≤ 3 |
| uptrend → `DOWNTREND` | ≥ 6 distribution days, or ≥ 5 together with a close below the 50-session average |
| `DOWNTREND` → `RALLY_ATTEMPT` | the first up close after a new low is day 1; later sessions count on (a down close that holds the low still counts) |
| Undercut | a low below the low of the attempt resets the count (day 1 again if that session closes up, else `DOWNTREND`) |
| Follow-through day | on day 4 or later of the attempt, index up ≥ 1.25 % on higher proxy volume → `CONFIRMED_UPTREND`; the distribution count restarts at 0 |

**Fixed window.** The state machine is path dependent, so it is run over exactly the last `window-sessions` (200)
index sessions ending at the session being labelled (plus 50 before them for the average). It opens in
`CONFIRMED_UPTREND` when the index is at or above its average at the window start, else in `DOWNTREND`. A label is
therefore a function of a fixed window of candles up to the session: no look-ahead, and the evening label equals what a
relabelling job started years earlier produces for the same session. (The plan carried the state in `DailyState`; a
state carried from the start of a job would make a label depend on where the job started.) In practice a follow-through
day or a run of distribution days inside 200 sessions decides the state, not the opening assumption.

Evidence: the sentence (`Market condition UPTREND_UNDER_PRESSURE: 4 distribution days in 25 sessions [dates]`) and the
features `distributionDays`, `distributionCount`, `rallyDay`, `followThroughDate`, `indexSma50`. The label is part of
the labelling hash; classifier version 2 introduced it (bumping the version relabels history). A change of the call
between two final labels publishes the `market_condition` client event and the `MARKET_CONDITION_CHANGED`
notification (INFO; WARNING into `DOWNTREND`). Surfaces: the regime API (`marketCondition`), the Market Pulse table
(`market.marketCondition` with `marketConditionEvidence`), the `get_market_regime` tool. It gates nothing until the
validation of plan M8.8 passes.

## Snapshots, storage and refresh

- `GET /api/v1/context/regime` returns the current session's snapshot: labels, `features` (the numbers above),
  `evidence` (one sentence per dimension), `classifierVersion`, `finalLabel`. The snapshot is cached for
  `intraday-snapshot` (5 min); the scheduler stores one intraday row per interval during the session
  (`market_regime_intraday`) and the final row at 15:35 IST (`market_regime`).
- `POST /api/v1/context/regime/label?from=&to=` labels every session in the range that has index data (final rows)
  and returns `{sessions, labelled, hash}`. It runs on startup for sessions missing under the current version.
- `GET /api/v1/context/regime/history?from=&to=` and `GET /api/v1/context/regime/intraday?date=` read the rows.

## Strategy preferences

`regime_preferences` keys (docs/strategy-dsl.md) match a snapshot as follows: `trending` (any UP/DOWN label),
`trending_up`, `trending_down`, `ranging`, `volatile` (HIGH or EXTREME), `quiet` (LOW or VERY_LOW), `gap` (any gap
label), `expiry`, and any lower-case dimension label (`strong_up`, `trend_day`, `gap_rejection`, ...).

## Regime-conditional statistics

`GET /api/v1/backtests/{id}` adds `byRegime` (trades grouped by the label of their entry session along
`trend × volatility`) and `similarRegime` (the bucket matching the current snapshot, with the overall expectancy
for comparison) or `similarRegimeNote` when there is none. `GET /api/v1/backtests/{id}/regimes?dims=trend,structure`
groups along other dimensions (`trend, volatility, opening, breadth, structure, event`). The grouping is computed
on read from the stored labels, so relabelling changes it without re-running the backtest. The strategy comparison
column `similarRegimePerformance` and the "Current regime" score adjuster (docs/hejje-score.md) use the same block.
