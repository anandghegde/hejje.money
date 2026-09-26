package money.hejje.broker.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import org.junit.jupiter.api.Test;

/** Plan M11.1: delivery fills move from today's CNC position to T1 and then to settled holdings, and survive a restart. */
class SimulatedHoldingsTest {

    static final UUID INFY = UUID.randomUUID();

    final MutableClock time = MutableClock.atIst("2026-12-04T10:00:00"); // a Friday
    final HejjeClock clock = new HejjeClock(time, MutableClock.IST, (d, e) -> false);
    final Map<String, String> saved = new HashMap<>();
    final PaperStateStore store = new PaperStateStore() {
        @Override
        public Optional<String> load(String key) {
            return Optional.ofNullable(saved.get(key));
        }

        @Override
        public void save(String key, String json) {
            saved.put(key, json);
        }
    };

    @Test
    void aBuyIsAPositionThenT1ThenSettledAcrossAWeekend() {
        SimulatedHoldings book = new SimulatedHoldings(clock, store, "t");
        book.onFill(INFY, "INFY", "NSE", Side.BUY, 10, new BigDecimal("100.00"));
        book.onFill(INFY, "INFY", "NSE", Side.BUY, 10, new BigDecimal("110.00"));
        assertThat(book.holdings(id -> null)).isEmpty();
        assertThat(book.positions(id -> null)).singleElement().satisfies(p -> {
            assertThat(p.product()).isEqualTo(Product.CNC);
            assertThat(p.netQuantity()).isEqualTo(20);
            assertThat(p.averagePrice()).isEqualByComparingTo("105.00");
        });

        time.setIst("2026-12-07T09:00:00"); // Monday: Friday's buys are T1
        SimulatedHoldings restarted = new SimulatedHoldings(clock, store, "t");
        assertThat(restarted.positions(id -> null)).isEmpty();
        assertThat(restarted.holdings(id -> new BigDecimal("112.00"))).singleElement().satisfies(h -> {
            assertThat(h.quantity()).isZero();
            assertThat(h.t1Quantity()).isEqualTo(20);
            assertThat(h.averagePrice()).isEqualByComparingTo("105.00");
            assertThat(h.lastPrice()).isEqualByComparingTo("112.00");
        });

        time.setIst("2026-12-08T10:00:00"); // settled; sell half today
        restarted.onFill(INFY, "INFY", "NSE", Side.SELL, 10, new BigDecimal("115.00"));
        assertThat(restarted.holdings(id -> null)).singleElement().satisfies(h -> {
            assertThat(h.quantity()).isEqualTo(20); // still listed until the sale settles
            assertThat(h.t1Quantity()).isZero();
        });
        assertThat(restarted.positions(id -> null)).singleElement().satisfies(p -> assertThat(p.netQuantity()).isEqualTo(-10));

        time.setIst("2026-12-09T10:00:00");
        assertThat(restarted.holdings(id -> null)).singleElement().satisfies(h -> assertThat(h.totalQuantity()).isEqualTo(10));
        assertThat(restarted.positions(id -> null)).isEmpty();
    }
}
