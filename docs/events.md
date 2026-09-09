# Event calendar and event risk (PRD §18, plan M3.3)

Events are structured, dated facts (results, ex-dates, RBI, FOMC, expiries, holidays), kept apart from news
sentiment (M3.4). The `events` module stores them in `market_event`, derives a proximity-based **event risk** per
instrument, applies each strategy's `event_rules`, and feeds the regime's event environment. It is optional: with
`hejje.events.enabled=false` (or when the store fails) the risk is `LOW` with an "unavailable" evidence line,
`available=false`, and nothing else in the trading path changes.

## Sources

| Source (`market_event.source`) | Flag | What it produces |
|---|---|---|
| `computed` | `hejje.events.computed.enabled` (true) | `HOLIDAY` from the exchange holiday calendar; `FNO_EXPIRY` from the option expiries of `expiry-underlyings` (the last expiry of a month is the monthly one); `INDEX_REBALANCE` from `index-rebalance-dates` |
| `curated` | `hejje.events.curated.enabled` (true) | `config/events/macro-2026.yaml`: RBI MPC, FOMC, India/US CPI, US jobs, Budget (`events: [{type, title, date, time?, end_date?, confidence?, symbol?}]`, IST times, all-day when `time` is absent; a resolving `symbol` makes it an instrument event) |
| `csv` | always | `POST /api/v1/events/import` with a `text/csv` body: header `type,symbol,title,date,time,end_date,confidence` (symbol/time/end_date/confidence optional, any order, quoted fields allowed). The manual path that always works for corporate actions, board meetings (with purpose) and results dates |
| `manual` | always | `POST /api/v1/events` (`strategies:write`) |
| `nse` | `hejje.events.nse.enabled` (**false**) | best-effort fetch of NSE's corporate-action and board-meeting JSON feeds (ex-dates classified into `EX_DIVIDEND | BONUS | SPLIT | BUYBACK | AGM | CORPORATE_ACTION`, board meetings mentioning results into `RESULTS`). Any failure (the site often blocks automated clients) logs once and yields nothing; use the CSV import instead |

Every source upserts by `(source, external_key)` where the key is `type|scope|symbol|date|title`, so refreshes never
duplicate and edits to a curated file replace the old row. Refresh runs after boot (`refresh-on-startup`), daily at
07:00 IST, and on `POST /api/v1/events/refresh?from=&to=` (`admin`); the default window is a week back to
`horizon-days` (60) ahead.

### Curated calendar maintenance (quarterly)

1. Copy `config/events/macro-2026.yaml` forward (or add a file and list it under `hejje.events.curated.files`).
2. RBI: the MPC schedule press release for the financial year (rbi.org.in). FOMC: federalreserve.gov meeting
   calendar; statements land at 14:00 ET, i.e. 00:30 IST the next day, so the Indian session reacts the morning
   after and the entry is dated that morning. India CPI: MoSPI, 16:00 IST on the 12th or the next working day.
   US CPI / jobs: bls.gov schedules (after the Indian close; listed so the next session's environment shows them).
3. Set `confidence` below 0.9 for dates that follow the pattern but are not yet confirmed by the issuer.
4. `POST /api/v1/events/refresh` (or restart) and check `GET /api/v1/events?from=&to=`.

## Event risk (`EventRiskEvaluator`)

For an instrument (market-only when none), over today's events plus the horizon:

| Situation | Level |
|---|---|
| results / earnings call today for the instrument | `HIGH` |
| macro event starting within `macro-high-within-minutes` (60) or in progress (`macro-in-progress-minutes`, 30, after a timed start; all day for all-day events) | `HIGH` |
| any other macro event today | `MEDIUM` |
| F&O expiry day when the instrument is a derivative of that underlying | `MEDIUM` |
| ex-date (dividend / bonus / split) or board meeting today for the instrument | `MEDIUM` |
| otherwise | `LOW` |

The highest matching rule wins; each match is one evidence line ("RBI MPC decision (2026-10-07 10:00, in 45 min)
→ HIGH"). Instrument events count for the instrument itself and, for derivatives, for their underlying's symbol.
`nextEvent` is the most imminent relevant event still ahead (or in progress), `minutesTo` its distance; the Today
card renders it as "Q2 Results — Today 16:00".

## Strategy event rules

`event_rules: { high_risk_event_within_minutes: N, action: block | caution | allow }` (docs/strategy-dsl.md). The rule
triggers when the instrument's risk is `HIGH` and the HIGH event is within N minutes (any HIGH event today when N is
absent; all-day events count as 0 minutes away). Then:

- `block` → the recommendation is `AVOID` with the hard block `eventRule: …`, and the risk pipeline's `eventRule`
  control rejects any `STRATEGY_SIGNAL` intent for that signal (so a manual execute fails deterministically; closing
  orders are never affected).
- `caution` → a `TRADE` recommendation becomes `TRADE_WITH_CAUTION` with the reason under risks; execution is allowed.
- `allow` → nothing.

When the event service is unavailable the rule is not applied (evidence says so).

## Where it shows

- Score adjuster "Event risk" (docs/hejje-score.md): HIGH −8, MEDIUM −3, LOW 0.
- Today header `eventRisk` (market level) and `nextEvent`; every recommendation carries `eventRisk` and `nextEvent`.
- Regime `eventEnvironment`: BUDGET > RBI > FED > MACRO_EVENT_SESSION > EARNINGS_HEAVY (≥ `earnings-heavy-count`
  results events) > EXPIRY_SESSION > NORMAL.
- Web: calendar widget on Today (next 7 days) and the Next Event line on the strategy page; TUI best card.
