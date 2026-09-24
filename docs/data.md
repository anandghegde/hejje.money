# Historical dataset (plan M2.4)

The strategy platform needs three or more years of intraday candles for the index, futures and equity universes.
Backfill runs on the VM against a live Kite session (tests never touch the broker); this page is the runbook.

## Series

| Series | Timeframes | Source |
|---|---|---|
| `INDEX:NIFTY 50`, `INDEX:NIFTY BANK`, `INDEX:INDIA VIX` | M1, M5, D1 | Kite historical API on the index token |
| NIFTY and BANKNIFTY futures, per contract | M1, M5, D1 | Kite historical API per contract instrument |
| `NFO:NIFTY:FUT:CONT`, `NFO:BANKNIFTY:FUT:CONT` continuous series | M1, M5, D1 | stitched from the contracts (below) |
| NIFTY 50 constituents (`config/universe/nifty50.yaml`) | M1, M5, D1 | Kite historical API per stock (M1 for SIM replays, plan M7.2) |
| NIFTY 500 constituents (`config/universe/nifty500.yaml`, with NSE's industry per symbol) | **D1 only**, from 2016 | Kite historical API per stock; the daily context universe (plan Phase 8) |

## Continuous futures

A backtest cannot use "the nearest future today" over three years, so the market module stitches a continuous series
per underlying: for every session the contract with the earliest expiry on or after the session date is used, and the
series rolls on the expiry date itself (the expiring contract is used up to the session before expiry, the next
contract from expiry day on). No back-adjustment is applied: intraday strategies never hold across a roll, so the price
gap between contracts never lands inside a trade. Raw per-contract candles are kept; the stitched series is written
under its own series id and served by `MarketService` like any instrument. Build it with
`POST /api/v1/market/history/continuous` after the contract backfills (see `docs/api.md`).

Availability caveat: Kite's instrument master lists only live contracts, so contracts that expired before the master
was first synced have no token and cannot be backfilled retroactively. The continuous series therefore starts with the
oldest contract present in the store; keep the daily instrument sync running so every new contract is captured and
backfilled before it expires.

## Backfill procedure (VM, admin API key)

```bash
export H=https://hejje.malgudi.app/api/v1 T="Authorization: Bearer $HEJJE_API_KEY"
# 1. instruments
curl -s -X POST -H "$T" $H/instruments/sync
# 2. indices (ids from /instruments/resolve?symbol=...)
for s in "INDEX:NIFTY 50" "INDEX:NIFTY BANK" "INDEX:INDIA VIX"; do
  id=$(curl -s -H "$T" "$H/instruments/resolve?symbol=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$s")" | jq -r .id)
  for tf in M1 M5 D1; do
    curl -s -X POST -H "$T" -H 'Content-Type: application/json' $H/market/history/backfill \
      -d "{\"instrumentId\":\"$id\",\"timeframe\":\"$tf\",\"from\":\"2023-01-01T00:00:00Z\",\"to\":\"2026-09-09T00:00:00Z\"}"
  done
done
# 3. NIFTY 50 constituents (M1, M5, D1) from config/universe/nifty50.yaml — same loop over the symbols list (M1 feeds SIM replays)
# 4. futures: every NIFTY/BANKNIFTY contract in the master, then stitch
curl -s -X POST -H "$T" -H 'Content-Type: application/json' $H/market/history/continuous -d '{"underlying":"NIFTY","timeframe":"M5"}'
```

Progress: `GET /market/history/jobs/{id}`. Minute data is fetched in ≤60-day chunks throttled to
`hejje.market.historical-per-second` (3/s): three years of M1 for one instrument is ~19 requests; the NIFTY 50 M5
backfill is ~50 × 7 requests.

## NIFTY 500 daily universe (plan M8.1)

`config/universe/nifty500.yaml` is generated, not edited: `python3 research/tools/gen_universe.py` downloads NSE's
`ind_nifty500list.csv`, checks every symbol against Kite's NSE instrument dump, reports the ones that do not resolve
(they stay in the file) and writes the symbols with their industry. Re-run it at the semi-annual index reviews and
commit the result.

```bash
# one-off backfill (admin key, CONNECTED Kite session): one parent job, a child per symbol under the 3/s throttle.
# D1 requests cover at most 2000 days, so ten years are two requests per symbol (~1,000 requests, about six minutes).
curl -X POST $H/api/v1/market/history/backfill-universe -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"universe":"nifty500","timeframe":"D1","from":"2016-01-01T00:00:00Z","to":"2026-09-20T00:00:00Z"}'
curl $H/api/v1/market/history/jobs/<jobId> -H "X-API-Key: $KEY"    # childrenDone / childrenFailed / failed / unresolved
```

Every evening (`hejje.ratings.daily-refresh-cron`, 18:30 IST on weekdays, only with `hejje.ratings.enabled=true`) the
last `hejje.ratings.refresh-sessions` (5) sessions of D1 are re-fetched for the universe (an upsert) and
`DailyCandlesRefreshed(date)` is published; ratings, bases and analogs chain off that event. A universe that is staler
than five sessions is brought current with `POST /api/v1/ratings/refresh {"sessions": N}`. In SIM the job is skipped.

Known limitations:

- **Survivorship bias.** The file holds today's constituents only. Stocks that were delisted or dropped out of the
  index are missing from every historical date, which flatters every historical statistic built on this universe
  (ratings percentiles, base outcomes, analog outcomes). The validation in plan M8.8 measures on recent data for that
  reason, and every daily-universe result carries the caveat.
- **Adjustments.** Kite's daily history is adjusted for splits and bonuses, not for dividends. An unadjusted split
  shows up in the integrity report's `suspectGaps` (below); the fix is to backfill the symbol again, there is no
  adjustment logic in Hejje.
- No point-in-time index membership, no BSE, no intraday data for the 500.

## Integrity report

`GET /api/v1/market/history/integrity?instrumentId=&timeframe=&from=&to=` compares the store with the holiday
calendar: expected sessions, sessions with data, missing sessions (first 50 listed), and per-session bar counts against
the expected count (375 for M1, 75 for M5, 25 for M15, 1 for D1) with the short sessions listed. Coverage alone is at
`GET /api/v1/market/history/coverage`. For D1 the report also lists `suspectGaps`: sessions whose open is 35 % or more
away from the previous close while `INDEX:NIFTY 50` gapped less than 5 % that day (`session`, `previousClose`, `open`,
`gapPct`; first 50), which is what an unadjusted split or bonus looks like.

## Baseline backtests

After backfilling, run for each bundled strategy (`docs/strategies/`) a `FIXED 60/20/20` and a `WALK_FORWARD 6/2`
backtest over the full range and record the metrics and warnings in the strategy page. Strategies that pass the
minimum-trade rule move to `VALIDATED`; the others stay `BACKTESTED` with the warning noted. Parameters are not
changed after seeing out-of-sample results.

## Seeding a development server

`POST /api/v1/market/dev/candles` (dev/test profiles) stores and/or publishes a scripted session for an instrument and
can feed quotes into the pipeline; `POST /api/v1/broker/dev/quote` pushes a quote into the fake broker. Together
with a forced `PAPER` status they drive the Playwright paper flow without any broker (`docs/analytics.md`).
