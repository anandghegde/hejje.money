package money.hejje.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Side;
import money.hejje.orders.Trade;
import money.hejje.scoring.internal.Comparisons;
import money.hejje.scoring.internal.RecentPerformanceAdjuster;
import org.junit.jupiter.api.Test;

class ComparisonsAndRoundTripsTest {

    static ComparisonRow row(int version, Integer trades, Double pf, Double exp, Double dd, Integer score) {
        return new ComparisonRow(UUID.randomUUID(), UUID.randomUUID(), "orb", version, "BACKTESTED", UUID.randomUUID(), trades, 0.55, pf, exp, dd, "n/a", score);
    }

    @Test
    void verdictNamesLargestImprovementAndRegression() {
        VersionComparison c = Comparisons.versions(row(2, 2012, 1.48, 0.25, 11.4, 78), row(3, 1842, 1.74, 0.42, 9.2, 84));
        assertThat(c.verdict()).isEqualTo("v3 improved: increased expectancy (r) by 68.0%, but reduced trades by 8.4%.");
        assertThat(c.deltas()).anySatisfy(d -> {
            assertThat(d.get("metric")).isEqualTo("Max drawdown (R)");
            assertThat(d.get("better")).isEqualTo(true); // lower drawdown is better
        });
        VersionComparison same = Comparisons.versions(row(1, 100, 1.5, 0.3, 5.0, 70), row(2, 100, 1.5, 0.3, 5.0, 70));
        assertThat(same.verdict()).contains("performs the same");
        ComparisonRow noBacktest = new ComparisonRow(UUID.randomUUID(), UUID.randomUUID(), "orb", 4, "DRAFT", null, null, null, null, null, null, "n/a", null);
        assertThat(Comparisons.versions(row(1, 100, 1.5, 0.3, 5.0, 70), noBacktest).verdict()).contains("need a completed backtest");
    }

    @Test
    void roundTripsPairFillsPerInstrument() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        List<Trade> fills = List.of(
                trade(a, Side.BUY, 10, "100", 1, s), trade(b, Side.SELL, 5, "50", 2, s), trade(a, Side.SELL, 10, "103", 3, s),
                trade(b, Side.BUY, 5, "48", 4, s), trade(a, Side.BUY, 4, "100", 5, s));
        List<BigDecimal> trips = RecentPerformanceAdjuster.roundTrips(fills);
        assertThat(trips).containsExactly(new BigDecimal("30"), new BigDecimal("10")); // the open 4-lot buy is not a round trip
    }

    static Trade trade(UUID instrument, Side side, int qty, String price, int minute, UUID strategy) {
        return new Trade(UUID.randomUUID(), UUID.randomUUID(), "t", instrument, side, qty, new BigDecimal(price), Instant.parse("2026-09-08T04:00:00Z").plusSeconds(60L * minute),
                ExecutionMode.PAPER, strategy);
    }
}
