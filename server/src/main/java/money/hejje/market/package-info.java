/**
 * Market data module: normalized ticks and candles, in-process tick bus, quote cache, live streamer, candle builder,
 * historical store (Postgres + Parquet/DuckDB), backfill, tick record/replay and the client market WebSocket.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.market;
