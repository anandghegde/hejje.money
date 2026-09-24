package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import money.hejje.common.Timeframe;
import org.junit.jupiter.api.Test;

class HistoricalBackfillJobTest {

    @Test
    void minuteChunksAreAtMost60DaysAndDoNotOverlap() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T00:00:00Z"); // ~180 days
        List<Instant[]> chunks = HistoricalBackfillJob.chunks(Timeframe.M1, from, to);
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks.get(0)[0]).isEqualTo(from);
        assertThat(chunks.get(chunks.size() - 1)[1]).isEqualTo(to);
        for (Instant[] chunk : chunks) {
            assertThat(Duration.between(chunk[0], chunk[1])).isLessThanOrEqualTo(Duration.ofDays(60));
        }
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i)[0]).isAfter(chunks.get(i - 1)[1]); // no overlap
        }
    }

    @Test
    void dailyChunksAreAtMost2000Days() {
        assertThat(HistoricalBackfillJob.chunks(Timeframe.D1, Instant.parse("2022-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"))).hasSize(1);
        List<Instant[]> tenYears = HistoricalBackfillJob.chunks(Timeframe.D1, Instant.parse("2016-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(tenYears).hasSize(2);
        assertThat(Duration.between(tenYears.get(0)[0], tenYears.get(0)[1])).isEqualTo(Duration.ofDays(2000));
    }
}
