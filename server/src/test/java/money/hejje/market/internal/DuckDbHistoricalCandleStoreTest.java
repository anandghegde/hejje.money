package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.config.HejjeProperties;
import money.hejje.market.Candle;
import money.hejje.market.CandleCoverage;
import money.hejje.market.ExecutionModeStub;
import money.hejje.market.ZoneStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuckDbHistoricalCandleStoreTest {

    static final UUID INSTR = UUID.randomUUID();

    static Candle candle(String isoUtc, double close) {
        Instant t = Instant.parse(isoUtc);
        BigDecimal c = BigDecimal.valueOf(close);
        return new Candle(INSTR, Timeframe.M1, t, c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 100, 0, false);
    }

    DuckDbHistoricalCandleStore store(Path dir) {
        return new DuckDbHistoricalCandleStore(new HejjeProperties(ExecutionModeStub.PAPER, ZoneStub.IST, dir));
    }

    @Test
    void writeReadRoundTripAndMerge(@TempDir Path dir) {
        DuckDbHistoricalCandleStore store = store(dir);
        store.write(INSTR, Timeframe.M1, List.of(candle("2026-09-08T03:45:00Z", 100), candle("2026-09-08T03:46:00Z", 101)));
        List<Candle> read = store.read(INSTR, Timeframe.M1, Instant.parse("2026-09-08T00:00:00Z"), Instant.parse("2026-09-08T23:59:00Z"));
        assertThat(read).hasSize(2);
        assertThat(read.get(0).close()).isEqualByComparingTo("100.00");
        assertThat(read.get(1).close()).isEqualByComparingTo("101.00");

        // merge: overlapping openTime is replaced, new ones appended, same year file
        store.write(INSTR, Timeframe.M1, List.of(candle("2026-09-08T03:46:00Z", 999), candle("2026-09-08T03:47:00Z", 102)));
        read = store.read(INSTR, Timeframe.M1, Instant.parse("2026-09-08T00:00:00Z"), Instant.parse("2026-09-08T23:59:00Z"));
        assertThat(read).hasSize(3);
        assertThat(read.get(1).close()).isEqualByComparingTo("999.00");
        assertThat(read.get(2).close()).isEqualByComparingTo("102.00");
    }

    @Test
    void coverageReportsRangeAndGaps(@TempDir Path dir) {
        DuckDbHistoricalCandleStore store = store(dir);
        assertThat(store.coverage(INSTR, Timeframe.M1).isEmpty()).isTrue();
        store.write(INSTR, Timeframe.M1, List.of(candle("2026-09-08T03:45:00Z", 100), candle("2026-09-08T03:50:00Z", 101)));
        CandleCoverage coverage = store.coverage(INSTR, Timeframe.M1);
        assertThat(coverage.candleCount()).isEqualTo(2);
        assertThat(coverage.from()).isEqualTo(Instant.parse("2026-09-08T03:45:00Z"));
        assertThat(coverage.to()).isEqualTo(Instant.parse("2026-09-08T03:50:00Z"));
        // a read of the gap in between returns nothing
        assertThat(store.read(INSTR, Timeframe.M1, Instant.parse("2026-09-08T03:46:00Z"), Instant.parse("2026-09-08T03:49:00Z"))).isEmpty();
    }

    @Test
    void writesSeparateYears(@TempDir Path dir) {
        DuckDbHistoricalCandleStore store = store(dir);
        store.write(INSTR, Timeframe.M1, List.of(candle("2025-12-31T09:00:00Z", 50), candle("2026-01-01T09:00:00Z", 60)));
        assertThat(store.read(INSTR, Timeframe.M1, Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-12-31T23:59:00Z"))).hasSize(2);
        assertThat(java.nio.file.Files.exists(dir.resolve("candles/M1/" + INSTR + "/2025.parquet"))).isTrue();
        assertThat(java.nio.file.Files.exists(dir.resolve("candles/M1/" + INSTR + "/2026.parquet"))).isTrue();
    }
}
