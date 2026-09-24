package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.market.Candle;
import money.hejje.market.DataIntegrityReport;
import org.junit.jupiter.api.Test;

class SuspectGapTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final UUID ID = UUID.randomUUID();

    private static Candle day(String date, String open, String close) {
        BigDecimal o = new BigDecimal(open);
        BigDecimal c = new BigDecimal(close);
        return new Candle(ID, Timeframe.D1, LocalDate.parse(date).atStartOfDay(IST).toInstant(), o, o.max(c), o.min(c), c, 1000, 0, false);
    }

    @Test
    void anUnadjustedSplitIsListedWithItsGap() {
        List<Candle> stock = List.of(day("2026-09-14", "1000", "1010"), day("2026-09-15", "505", "500"), day("2026-09-16", "500", "520"));
        List<Candle> index = List.of(day("2026-09-14", "25000", "25050"), day("2026-09-15", "25060", "25100"), day("2026-09-16", "25100", "25000"));
        List<DataIntegrityReport.SuspectGap> gaps = HistoryIntegrity.suspectGaps(stock, index, IST);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).session()).isEqualTo(LocalDate.parse("2026-09-15"));
        assertThat(gaps.get(0).gapPct()).isEqualTo(-50.0);
        assertThat(gaps.get(0).previousClose()).isEqualByComparingTo("1010");
    }

    @Test
    void aGapBelowTheThresholdOrSharedByTheIndexIsNotSuspect() {
        List<Candle> stock = List.of(day("2026-09-14", "1000", "1000"), day("2026-09-15", "700", "700"), day("2026-09-16", "420", "420"));
        List<Candle> index = List.of(day("2026-09-14", "25000", "25000"), day("2026-09-15", "24900", "24900"), day("2026-09-16", "22000", "22000"));
        // 15th: -30 % is under the threshold; 16th: -40 % while the index itself gapped -11.6 % (a market-wide move)
        assertThat(HistoryIntegrity.suspectGaps(stock, index, IST)).isEmpty();
    }

    @Test
    void withoutIndexCandlesEveryLargeGapIsSuspect() {
        List<Candle> stock = List.of(day("2026-09-14", "100", "100"), day("2026-09-15", "200", "200"));
        assertThat(HistoryIntegrity.suspectGaps(stock, List.of(), IST)).hasSize(1);
    }
}
