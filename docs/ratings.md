# Daily ratings, groups, bases and lists (plan Phase 8)

Hejje's nightly research layer over the NIFTY 500 (`config/universe/nifty500.yaml`, D1 candles only). It is **context
and evidence**: nothing on this page places an order, and until the validation in
[`strategies/context-validation.md`](strategies/context-validation.md) passes, nothing here changes a score, a decision
or a universe. Module `money.hejje.ratings`, off by default (`hejje.ratings.enabled`); thresholds in
`config/ratings.yaml`.

Rules that hold for every number below:

- **No look-ahead.** A row for session `D` is a function of candles up to and including `D`'s close. The API never
  serves a session that has not closed on the Hejje clock (in SIM: the simulation clock), so a request for "today"
  before 15:30 returns the previous session.
- **Immutable rows.** A row is keyed `(session_date, instrument, engine_version)` and written once. Changing a formula
  or a threshold means bumping `hejje.ratings.engine-version`; old rows stay.
- **Reproducible.** The same candles and engine version give byte-identical rows; every compute job returns a SHA-256
  over its rows.
- **Known limitation: survivorship bias.** The universe is today's index membership (see `docs/data.md`). Percentiles
  on old dates rank only the survivors.
- Ratings are statistics, not money: they are `double`s rounded to six decimals.

## Ratings (`daily_rating`)

All windows count the stock's own sessions. `close[n]` is the close `n` sessions ago.

| Field | Formula | Needs |
|---|---|---|
| `rsRaw` | `0.4·ret(63) + 0.2·ret(126) + 0.2·ret(189) + 0.2·ret(252)`, `ret(n) = close / close[n] − 1` | 63 sessions. A younger listing uses the quarters it has, weights renormalised, `evidence.partial = true`, `evidence.rsQuarters` = how many |
| `rsRating` | 1–99: `round(1 + 98·p)`, `p` = share of the other rated stocks that day with a strictly lower `rsRaw` | `rsRaw` |
| `adRaw` | `Σ clv·volume / Σ volume` over 65 sessions, `clv = ((close−low) − (high−close)) / (high−low)`, 0 on a zero range; −1..+1 | 65 sessions |
| `adGrade` | universe percentile `p` of `adRaw` in 13 equal-width bands, best first: `A+ A A− B+ B B− C+ C C− D+ D D− E`; band = `floor((1−p)·13)` | `adRaw` |
| `offHighPct` | percent below the highest high of 252 sessions (0 at the high) | 1 session (uses what exists) |
| `offLowPct` | percent above the lowest low of 252 sessions | 1 session |
| `volVsAvg50Pct` | the session's volume vs the mean of the **previous** 50 sessions, in percent (`+40` = 1.4×) | 51 sessions |
| `upDownVolRatio` | volume on up closes / volume on down closes over 50 sessions; null without a down close | 51 sessions |
| `avgTurnoverCr` | mean `close × volume` over 50 sessions, in ₹ crore | 1 session |
| `changePct`, `close` | the session's change and close | — |
| `techComposite` | 1–99 percentile of `0.5·pRS + 0.2·pAD + 0.15·pGroup + 0.15·pOffHigh` (component percentiles 0..1; an unranked group counts 0.5) | `rsRaw` and `adRaw` |

The composite is called **technical** everywhere: O'Neil's Composite rating needs EPS and SMR, and Kite has no
fundamentals. `evidence` carries the sessions of history, the partial flag, the universe size that day and the four
component percentiles.

## Industry groups (`industry_group_rank`)

One group per `industry` in the universe file (NSE's macro industries, about twenty). `strength` is the median `rsRaw`
of the members that have one; a group is ranked when at least `min-group-members` (3) do. `rank` 1 is the strongest. A
stock in an unranked group has `groupRank = null`.

## Sessions

A date is a session when at least `min-session-coverage` (20 %) of the universe has a D1 candle on it, which keeps a
stray candle on a holiday from creating a session.

## Bases (`base`, `base_status_history`)

Deterministic detectors on D1, thresholds under `hejje.ratings.bases.*`. A detector looks at a session and the sessions
before it only and answers whether a pattern that has not broken out yet is complete as of that session. Every base
needs a **prior uptrend**: its left high must be the highest high of the 10 sessions before it and stand at least 25 %
above the lowest low of the 120 sessions before it. Detectors run in the order cup (with or without handle), double
bottom, flat base; the first match wins.

| Type | Rule | Pivot |
|---|---|---|
| `FLAT_BASE` | ≥ 25 sessions below the left high (the highest high of the last 65 sessions), never more than 15 % under it | the left high |
| `CUP_WITH_HANDLE` | left high → low 12–35 % below it → right high at ≥ 90 % of the left high, 35–325 sessions from high to high; then a handle of 5–30 sessions whose low stays in the upper half of the cup and no more than 12 % under the right high | the handle's high (= the right high) |
| `CUP` | the same cup with fewer than 5 sessions since the right high | the left high |
| `DOUBLE_BOTTOM` | ≥ 35 sessions from the left high; two lows within 3 % of each other, ≥ 10 sessions apart, a middle peak ≥ 8 % above the higher low and below the left high; depth ≤ 35 %; the second low is ≥ 3 sessions old and no close has reached the pivot. The second low may undercut the first or not (`double-bottom-require-undercut: false`) | the middle peak |
| `MA_REVERSAL` | TraderSmith's trend reversal day: close above a 50-DMA that is higher than 10 sessions ago, the session's low within 0.5 % above the 20- or the 50-DMA (or below it), the close above that average and in the upper 40 % of the session's range, and the close ≥ 25 % above the 120-session low | the session's high; stop = its low |

An instrument has at most one open base and one open reversal setup; a left high that produced a base never produces
another. One exception: a `CUP` that has not triggered is closed (`EXPIRED`) when the `CUP_WITH_HANDLE` forms from it.

**Trade plan** (tick-rounded `BigDecimal`s, informational: nothing in Hejje trades it): buy zone `[pivot, pivot × 1.05]`,
stop `pivot × 0.93` (reversal: the session's low), goal `pivot × 1.20` (reversal: `× 1.08`).

**Lifecycle**, advanced once per session, starting the session after detection:

| Status | Meaning |
|---|---|
| `FORMING` / `NEAR_PIVOT` | not triggered; the close is more than / within 5 % below the pivot |
| `IN_BUY_ZONE` | **trigger** = the first close at or above the pivot. Assumed entry: the pivot, or that session's open when it opened above the pivot. `volumeConfirmed` = the trigger session's volume ≥ 1.4 × the mean of the previous 50 sessions. Later: any close inside the zone |
| `EXTENDED` / `PULLBACK` | triggered; the close is above the zone / back below the pivot with the stop not hit (`PULLBACK` is an addition to the plan's list) |
| `HIT_GOAL` / `STOPPED` | from the session after the trigger: low ≤ stop → `STOPPED` at the stop (at the open when it gapped through); else high ≥ goal → `HIT_GOAL` likewise. Both in one session: the stop wins |
| `FAILED` | never triggered and the close fell below the base low (reversal: the low traded through the stop). The plan's wording ("closes back below the pivot by > 7 %") coincides with the stop once a base has triggered, so `FAILED` is kept for structures that break before they trigger |
| `EXPIRED` | not triggered within 60 sessions of detection, or triggered and neither goal nor stop within 120 sessions (closed at that close) |

A closed, triggered setup carries `exit`, `outcomePct` and `outcomeR` (`(exit − entry) / (entry − stop)`): the
**past-setups ledger**. `base` rows are written once; every status is a row of the append-only
`base_status_history`, and a read "as of" a session uses the newest status row on or before it, so an earlier date (or
a SIM clock) never sees a later outcome. `base_progress` holds the last processed session per instrument: a run always
continues right after it, so a re-run is a no-op and two partial runs equal one full run.

Known limitations: no weekly charts, ascending bases, high tight flags or base counting; a cup's roundness is not
checked (a V-shaped recovery qualifies); volume dry-up in the handle is not required; survivorship bias applies to
the ledger.

## Lists

`GET /api/v1/ratings/lists/{name}` over a session's ratings and the bases as of that session
(`hejje.ratings.lists.*`):

| Name | Rows |
|---|---|
| `setups` | the combined ordering: in the buy zone, then triggered and away from it (`EXTENDED`, `PULLBACK`), then near the pivot; each tier by technical composite |
| `buyzone`, `nearpivot` | one tier of the above |
| `leaders` | technical composite ≥ 85, RS ≥ 80, mean turnover ≥ ₹10 cr |
| `movers` | stocks on the move: \|change\| ≥ 2 % on ≥ 1.5 × the 50-session average volume, up and down, by absolute change |
| `groups` | the industry group ranks |

Alerts (`ENTERED_BUY_ZONE`, `NEAR_PIVOT`, `SETUP_STOPPED`, `SETUP_HIT_GOAL`, docs/notifications.md) fire from the nightly
run for the session's Leaders (and, from M8.7, the watchlist), at most once per symbol per day.

## Screener, saved screens and watchlist

`POST /api/v1/ratings/screen` filters one flat row of fields per stock (ratings, the stock's open base, watchlist
membership, and the daily-analog evidence the analogs module contributes) by field / operator / value, all filters
ANDed; nothing richer than that on purpose. The field list is fixed (`docs/api.md`); a missing value never matches. A
saved screen is a stored request; the M8.4 lists are seeded as screens. The **watchlist** extends two things beyond the
Leaders: the nightly setup alerts, and the instruments whose session analogs are computed at every checkpoint.

The evening **digest** (`DAILY_CONTEXT_DIGEST`, one in-app line per evening): the market condition, the session's new
buy-zone entries, and the top five of the ranked analog list, each with its count.

## Surveillance (NSE ASM/GSM)

Whether a stock is under one of NSE's surveillance measures: **ASM** long term (stages I–IV) or short term (stages I–II)
and **GSM** (stages 0–IV and VI). **Display only**: the flag is on the ratings, the lists and the screener, and it changes
no score, list, universe or decision.

**Sources (checked with live calls on 2026-09-26).** NSE's reports pages (`https://www.nseindia.com/reports/asm`, `.../gsm`)
are fed by two JSON endpoints, which Hejje calls:

| Endpoint | Shape |
|---|---|
| `GET https://www.nseindia.com/api/reportASM` | `{ "longterm": { "data": [row] }, "shortterm": { "data": [row] } }`; row `{ srno, symbol, companyName, isin, series (null), asmSurvIndicator ("Stage I"), survCode ("LTASM - I (13)", "STASM - II (12)"), survDesc, asmTime ("25-Sep-2026") }` |
| `GET https://www.nseindia.com/api/reportGSM` | `[row]`; row `{ srno, symbol, companyName, isin, gsmStage (a roman numeral of the code number, e.g. "LXII"), survCode ("GSM - VI (6)", "IBC - Receipt & GSM 0 (62)", "GSM IV & IBC - Receipt (66)"), survDesc, gsmTime ("25-Sep-2026 08:08:02") }` |

The same reports as CSV: append `?csv=true` (columns `SR. NO, SYMBOL, COMPANY NAME, ISIN, ASM STAGE` / `GSM STAGE`, the
ASM file with "Long Term" / "Short Term" section rows and no date); Hejje uses the JSON because it carries the date. On
the day checked: 138 long-term ASM (stage I 121, II 4, III 2, IV 11), 77 short-term ASM (I 74, II 3) and 77 GSM rows;
the three lists were disjoint. **Access:** no cookie or session is needed, but NSE's edge drops a request without a
browser-like `User-Agent` (curl's default times out); Java's `HttpClient` with the User-Agent Hejje sends answered 200 over
HTTP/2. Not checked from the VM: a datacenter address may be treated differently, which the failure mode below covers.

**Stage.** Read from `survCode`, not `gsmStage`: `LTASM - II (14)` is `ASM_LT_2`, `STASM - I (11)` is `ASM_ST_1`, and a GSM
code names its stage after "GSM" wherever it stands (`IBC - Receipt & GSM 0 (62)` is `GSM_0`, `GSM IV & IBC - Receipt (66)`
is `GSM_4`). A row whose code does not parse is skipped with a warning. One flag per symbol; should a symbol ever appear on
more than one list, GSM wins over long-term ASM over short-term ASM. NSE's full code is kept as `code` (it shows the IBC /
ESM / LTASM part of a combined GSM code).

**Fetch and storage.** The evening D1 refresh fetches both reports after the candles (and `POST /api/v1/ratings/surveillance/refresh`
on demand) and stores them as the snapshot of that day's session (`surveillance_snapshot`, `surveillance_flag`; the dates
NSE prints are kept as `asm_date`, `gsm_date`); a re-fetch the same day replaces the snapshot. Only while the ratings are
enabled, and `hejje.ratings.surveillance.enabled` (default true) switches the fetch off on its own. A failed fetch (HTTP
error, block page, other shape, both lists empty) is logged and stores nothing; the ratings run carries on.

**Reads.** A rating of session `D` carries `surveillance: { flag, code, asOf, stale }` from the newest snapshot on or
before `D`: `flag` is `NONE` for a stock on no list, `asOf` the snapshot's session, `stale` true when that is before `D`
(the day's fetch failed or has not run). Before the first snapshot `surveillance` is null (unknown, not `NONE`). The
screener field `surveillance` is the flag. The flag is attached when read: it is not part of the stored rating row or its
hash. SIM never fetches (the evening refresh is skipped there) and reads only snapshots on or before the simulated
session.

## Jobs

- Evening: the D1 refresh (`docs/data.md`) fetches NSE's surveillance lists, then `DailyCandlesRefreshed(date)` computes
  the day's ratings, advances and detects bases, then publishes the setup alerts.
- History: `POST /api/v1/ratings/compute {from, to}`. Sessions that already have rows under the engine version are
  skipped, so re-running is a no-op; the returned hash covers every row of the range, stored or new.
- Bases history: `POST /api/v1/ratings/bases/compute {from, to}`; the hash covers every base detected up to `to` with its
  status as of `to`.
