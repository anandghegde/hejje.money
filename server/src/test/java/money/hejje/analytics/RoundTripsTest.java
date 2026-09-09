package money.hejje.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Side;
import money.hejje.orders.Trade;
import org.junit.jupiter.api.Test;

class RoundTripsTest {

    static Trade fill(UUID instrument, UUID strategy, Side side, int qty, String price, int minute) {
        return new Trade(UUID.randomUUID(), UUID.randomUUID(), "b", instrument, side, qty, new BigDecimal(price), Instant.parse("2026-09-08T04:00:00Z").plusSeconds(60L * minute),
                ExecutionMode.PAPER, strategy);
    }

    @Test
    void buildsRoundTripsPerInstrumentAndStrategyWithAveragedPrices() {
        UUID a = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        List<Trade> fills = List.of(
                fill(a, s, Side.BUY, 10, "100", 1), fill(a, s, Side.BUY, 10, "102", 2), fill(a, s, Side.SELL, 20, "105", 3), // long: avg 101 -> 105
                fill(a, null, Side.SELL, 5, "50", 4), fill(a, null, Side.BUY, 5, "48", 5),                                   // manual short
                fill(a, s, Side.BUY, 3, "100", 6));                                                                          // still open
        List<RoundTrip> trips = AnalyticsService.roundTrips(fills, t -> Money.ofRupees(1), id -> Optional.empty());
        assertThat(trips).hasSize(2);
        RoundTrip first = trips.get(0);
        assertThat(first.side()).isEqualTo(Side.BUY);
        assertThat(first.quantity()).isEqualTo(20);
        assertThat(first.entryPrice()).isEqualByComparingTo("101.00");
        assertThat(first.exitPrice()).isEqualByComparingTo("105.00");
        assertThat(first.grossPnl()).isEqualTo(Money.of("80.00"));
        assertThat(first.fees()).isEqualTo(Money.ofRupees(3));
        assertThat(first.netPnl()).isEqualTo(Money.of("77.00"));
        assertThat(first.strategyId()).isEqualTo(s);
        RoundTrip second = trips.get(1);
        assertThat(second.strategyId()).isNull();
        assertThat(second.side()).isEqualTo(Side.SELL);
        assertThat(second.grossPnl()).isEqualTo(Money.of("10.00"));
        assertThat(second.holdingMinutes()).isEqualTo(1);
    }

    @Test
    void slippageInBasisPoints() {
        assertThat(ReviewService.bps(new BigDecimal("1507.50"), new BigDecimal("1507.00"), true)).isCloseTo(3.32, org.assertj.core.data.Offset.offset(0.01));
        assertThat(ReviewService.bps(new BigDecimal("1494.00"), new BigDecimal("1495.00"), false)).isCloseTo(6.69, org.assertj.core.data.Offset.offset(0.01)); // sold below the stop
        assertThat(ReviewService.bps(new BigDecimal("100"), null, true)).isZero();
    }
}
