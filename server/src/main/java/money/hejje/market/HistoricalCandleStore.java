package money.hejje.market;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;

/** Long-term candle store (Parquet via DuckDB). Operational recent candles live in Postgres (see MarketService). */
public interface HistoricalCandleStore {

    /** Upserts candles (idempotent by instrument, timeframe and openTime). */
    void write(UUID instrumentId, Timeframe timeframe, List<Candle> candles);

    /** Reads candles in {@code [from, to]} (both inclusive), ordered by openTime. */
    List<Candle> read(UUID instrumentId, Timeframe timeframe, Instant from, Instant to);

    CandleCoverage coverage(UUID instrumentId, Timeframe timeframe);

    /** Writes completed since start (counted after each one), so a cache of what was read can tell that nothing changed. */
    long writes();
}
