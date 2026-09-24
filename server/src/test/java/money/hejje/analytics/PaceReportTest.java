package money.hejje.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pace buckets by trades that day, sequence within the day and entry hour, with counts (plan M9.7). */
class PaceReportTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static TradeFact trade(String day, String hhmm, long netRupees, Double r) {
        Instant at = LocalDate.parse(day).atTime(java.time.LocalTime.parse(hhmm)).atZone(IST).toInstant();
        return new TradeFact(UUID.randomUUID(), UUID.randomUUID(), "NSE:X", UUID.randomUUID(), "orb", "BREAKOUT", at, at.plusSeconds(600), "BUY", 10,
                BigDecimal.TEN, BigDecimal.TEN, netRupees * 100, 0, netRupees * 100, r, "RANGE", "RANGE", "LOW", "NEUTRAL", "TARGET", null, null, null, null,
                at.atZone(IST).getHour());
    }

    @Test
    void bucketsByDayCountSequenceAndHour() {
        List<TradeFact> facts = new ArrayList<>();
        // a quiet day: 2 trades, both winners at +1R
        facts.add(trade("2026-09-01", "09:45", 200, 1.0));
        facts.add(trade("2026-09-01", "11:10", 200, 1.0));
        // a busy day: 6 trades; the first two win, the rest lose −1R (one without a stop)
        String[] times = {"09:30", "09:50", "10:20", "10:40", "11:05", "13:30"};
        for (int i = 0; i < 6; i++) {
            facts.add(trade("2026-09-02", times[i], i < 2 ? 150 : -100, i < 2 ? Double.valueOf(1.0) : i == 5 ? null : Double.valueOf(-1.0)));
        }
        PaceReport p = money.hejje.analytics.PerformanceMath.pace("PAPER", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), null, facts, IST);
        assertThat(p.trades()).isEqualTo(8);
        assertThat(p.byTradesThatDay()).extracting(PaceReport.Row::bucket, PaceReport.Row::trades).containsExactly(
                org.assertj.core.groups.Tuple.tuple("1-4", 2), org.assertj.core.groups.Tuple.tuple("5-8", 6), org.assertj.core.groups.Tuple.tuple("9-16", 0),
                org.assertj.core.groups.Tuple.tuple("17+", 0));
        PaceReport.Row quiet = p.byTradesThatDay().get(0);
        assertThat(quiet.winRate()).isEqualTo(1.0);
        assertThat(quiet.expectancyR()).isEqualTo(1.0);
        assertThat(quiet.expectancyRupees()).isEqualByComparingTo("200.00");
        PaceReport.Row busy = p.byTradesThatDay().get(1);
        assertThat(busy.wins()).isEqualTo(2);
        assertThat(busy.withR()).isEqualTo(5);
        assertThat(busy.expectancyR()).isEqualTo(-0.2);          // (1 + 1 − 1 − 1 − 1) / 5
        assertThat(busy.expectancyRupees()).isEqualByComparingTo("-16.67"); // (300 − 400) / 6
        assertThat(busy.netPnl()).isEqualByComparingTo("-100");
        assertThat(p.bySequence()).extracting(PaceReport.Row::bucket, PaceReport.Row::trades).containsExactly(
                org.assertj.core.groups.Tuple.tuple("1st", 2), org.assertj.core.groups.Tuple.tuple("2nd", 2), org.assertj.core.groups.Tuple.tuple("3rd", 1),
                org.assertj.core.groups.Tuple.tuple("4th", 1), org.assertj.core.groups.Tuple.tuple("5th", 1), org.assertj.core.groups.Tuple.tuple("6th+", 1));
        assertThat(p.bySequence().get(2).expectancyR()).isEqualTo(-1.0);
        assertThat(p.byHour()).extracting(PaceReport.Row::bucket).containsExactly("09", "10", "11", "13");
        assertThat(p.byHour().get(0).trades()).isEqualTo(3);
        assertThat(p.bySequence().get(5).expectancyR()).isNull(); // the only 6th trade had no stop
    }
}
