package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import money.hejje.common.event.MarketTick;
import money.hejje.market.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Recording a day's ticks to Parquet and replaying them yields exactly the same candles as the original run. */
class TickReplayParityTest {

    @Test
    void replayYieldsIdenticalCandles(@TempDir Path dir) throws Exception {
        List<MarketTick> ticks = CandleBuilderTest.fixtureTicks();
        List<Candle> live = CandleBuilderTest.buildAll(ticks);

        Path file = dir.resolve("2026-09-08").resolve("ticks.parquet");
        Files.createDirectories(file.getParent());
        TickRecorder.writeTicks(file, ticks);

        List<MarketTick> replayed = ReplayMarketDataSource.readFile(file);
        assertThat(replayed).usingRecursiveComparison().isEqualTo(ticks);

        List<Candle> fromReplay = CandleBuilderTest.buildAll(replayed);
        assertThat(fromReplay).usingRecursiveComparison().isEqualTo(live);
    }

    @Test
    void orderBookFieldsRoundTrip(@TempDir Path dir) throws Exception {
        List<MarketTick> ticks = BarMicroTest.ticks();
        Path file = dir.resolve("ticks.parquet");
        TickRecorder.writeTicks(file, ticks);
        List<MarketTick> replayed = ReplayMarketDataSource.readFile(file);
        assertThat(replayed).usingRecursiveComparison().isEqualTo(ticks);
        assertThat(BarMicroTest.run(replayed).micro()).isEqualTo(BarMicroTest.run(ticks).micro());
    }

    /** A day file written before plan M9.4 (eight columns) replays with the order-book fields null and no error. */
    @Test
    void anOldSchemaFileReplaysWithoutMicro(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("old.parquet");
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:duckdb:"); java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (instrument_id VARCHAR, ts TIMESTAMP, last_price DOUBLE, bid DOUBLE, ask DOUBLE, volume BIGINT, oi BIGINT, mode VARCHAR)");
            st.execute("INSERT INTO t VALUES ('" + BarMicroTest.ID + "', TIMESTAMP '2026-09-08 03:50:05', 100.0, 99.9, 100.1, 1000, 0, 'FULL'),"
                    + " ('" + BarMicroTest.ID + "', TIMESTAMP '2026-09-08 03:50:30', 100.2, 100.1, 100.3, 1500, 0, 'FULL')");
            st.execute("COPY t TO '" + file.toAbsolutePath() + "' (FORMAT PARQUET)");
        }
        List<MarketTick> replayed = ReplayMarketDataSource.readFile(file);
        assertThat(replayed).hasSize(2).allSatisfy(t -> {
            assertThat(t.hasBook()).isFalse();
            assertThat(t.bid()).isNotNull();
        });
        assertThat(BarMicroTest.run(replayed).micro()).isEmpty();
        // a recorder appending to that day rewrites the file in the new schema
        TickRecorder.writeTicks(file, new java.util.ArrayList<>(replayed));
        assertThat(ReplayMarketDataSource.readFile(file)).hasSize(2);
    }
}
