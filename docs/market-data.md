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
the cumulative volume at the candle's open; derived candles sum their minute volumes. The baseline restarts at zero on
an instrument's first tick of a new IST day (before M7.2 the day's first minute measured against yesterday's cumulative
and came out as zero volume).

A derived candle (M3/M5/M15/H1) closes as soon as its last minute closes, at its own close time: the 09:25 M5 bar is
published at 09:30:00 (before M7.2 it waited for the next period's first minute, a minute late).

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

Traded instruments stream too (`TradedInstruments`): an order intent subscribes its instrument (manual orders, stops,
closes), and at startup every open position's instrument is subscribed again, so paper stops trigger and position P&L
moves for stocks no strategy watches. Only when `hejje.market.stream-on-startup` is on (off in tests and the e2e stack).


## Order book and flow (plan M9.4)

**Which modes carry depth.** Only **FULL**-mode Kite ticks carry the five best bid and ask levels and the exchange's
day totals of pending buy and sell quantity; `KiteMapper` fills `bidQty5` / `askQty5` (sums of the five levels'
quantities) and `totalBuyQty` / `totalSellQty` from them. LTP and QUOTE ticks leave the four fields null, as do the
Dhan adapter (its polled quotes have no depth) and the SIM replay from M1 candles. The watchlist and option chains
stream in FULL mode (above); a deployment's instruments stream LTP unless they are also on the watchlist.

**Recording.** The day file has four more nullable columns (`bid_qty5`, `ask_qty5`, `total_buy_qty`,
`total_sell_qty`). Files written before them replay with the fields null and no error (the reader checks the schema;
old files are not migrated, but a recorder appending to such a day rewrites it in the new schema).

**Per-bar features** (`BarMicro`, built in `MarketPipeline` beside the candles from the same ticks):

| Field | Definition |
|---|---|
| `imbalanceClose` | (bid5 − ask5) / (bid5 + ask5) at the bar's last tick with both quantities |
| `imbalanceMean` | the mean of that imbalance over the bar's ticks with depth (tick-weighted) |
| `buySellRatio` | totalBuy / totalSell at the bar's last tick carrying both (totalSell > 0) |
| `upVolumeShare` | up / (up + down) volume, where each tick's volume delta over the previous tick of the day is signed by the price change and unchanged-price ticks are ignored |
| `ticks`, `depthTicks` | ticks in the bar, and those with depth |

M1 values are rolled up into M3/M5/M15/H1 like candles: the last minute's imbalance and ratio, the depth-tick-weighted
mean, summed up/down volume. A bar has micro data only when at least one of its ticks carried order-book fields;
synthetic minutes never do.

**The approximation.** `upVolumeShare` signs **polled cumulative volume** between consecutive ticks, not individual
trade prints: several trades between two ticks are one delta signed by the net price change, and volume on an
unchanged price is dropped. Treat it as a coarse flow proxy (as warrenduffer's `flowShare`), not a trade-by-trade
buy/sell split.

**Storage.** `bar_micro` (V46) in Postgres for the operational window, pruned with `market_candle`
(`retention-sessions`); never written to the Parquet history. `MarketService.micro(instrument, timeframe, from, to)`
reads it (in SIM capped at the simulation clock). Closed-candle events carry the bar's micro data to the signal engine
and to bots' decision points (`micro`, null when absent).

**Live-only rule.** Historical candles carry no depth or tick flow. The indicators that read these values
(`book_imbalance`, `book_imbalance_mean`, `buy_sell_qty_ratio`, `flow_up_share(n)`, docs/indicators.md) are
`NOT_READY` on bars without micro data, so in a backtest over history a rule using them never passes, and the
backtester says so (`MICROSTRUCTURE_NOT_READY`). Such a rule can only pass on live or recorded ticks.
