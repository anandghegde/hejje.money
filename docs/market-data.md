# Market data

Two candle stores, one merged view via MarketService.candles:

- Recent candles live in Postgres `market_candle` (last ~15 sessions, `hejje.market.retention-sessions`), written as
  they close and pruned nightly.
- Historical candles live in Parquet at `${hejje.data-dir}/candles/{timeframe}/{instrumentId}/{yyyy}.parquet`, read and
  written through DuckDB (`read_parquet`, `COPY ... TO ... (FORMAT PARQUET)`). Timestamps are stored as UTC wall-clock
  and read back as UTC.

## Pipeline

Every tick (live from the broker stream, or replayed) flows through one MarketPipeline:

1. update QuoteCache,
2. record the tick when `hejje.market.record=true`,
3. publish the tick on the in-process TickBus (bounded, single dispatcher thread, drop-oldest on overflow),
4. feed CandleBuilder: ticks aggregate into 1-minute candles aligned to IST minute boundaries; M3/M5/M15/H1 derive from
   the 1-minute stream; a minute with no tick becomes a synthetic candle (o=h=l=c=previous close, volume 0,
   synthetic=true); each closed candle is persisted to Postgres and published as CandleClosedEvent on the bus.

A scheduled tick (1 s) closes minutes whose boundary has passed so empty minutes still produce synthetic candles.

Candle volume: broker ticks carry cumulative day volume, so a candle's volume is the last tick's cumulative volume minus
the cumulative volume at the candle's open; derived candles sum their minute volumes.

## Streaming

MarketDataStreamer owns the broker streaming connection: the watchlist in FULL mode, ad-hoc subscriptions in LTP,
reconnect with exponential backoff (capped 30 s), and gap detection. Readiness line `marketData`: OK while ticks are
fresh during the session, BLOCKING (STALE) after `stale-after` with no tick, SKIPPED outside the session or when
streaming is disabled.

## History backfill

`POST /market/history/backfill` runs HistoricalBackfillJob: minute data chunked into <=60-day broker requests, throttled
to `historical-per-second`, stored in Parquet; progress via `GET /market/history/jobs/{id}`; gaps visible via
`GET /market/history/coverage`.

## Record and replay

`hejje.market.record=true` writes ticks to `${data-dir}/ticks/{date}/ticks.parquet`. ReplayMarketDataSource (dev
profile) replays a recorded day into the same pipeline at a configurable speed, so replayed candles are byte-identical
to the live run (see TickReplayParityTest).

Subscription modes (M5.4): the watchlist and option chains stream in FULL mode (volume, open interest, depth; the chain's
put/call ratios and max pain need OI); every other subscription is LTP. An instrument already in FULL mode is never
downgraded by a later LTP subscription.

