package money.hejje.risk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Side;
import money.hejje.orders.Trade;
import org.junit.jupiter.api.Test;

/** Today's entries (one per opening order) and closed round trips from the fills (plan M9.7). */
class AccountDayTest {

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();

    static Trade fill(UUID order, UUID instrument, Side side, int qty, String price, int minute) {
        return new Trade(UUID.randomUUID(), order, "b", instrument, side, qty, new BigDecimal(price), Instant.parse("2026-09-08T04:00:00Z").plusSeconds(60L * minute),
                ExecutionMode.PAPER, null);
    }

    @Test
    void entriesAreOpeningOrdersAndClosesCarryTheirRealizedPnl() {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        List<Trade> trades = List.of(
                fill(e1, A, Side.BUY, 5, "100.00", 0), fill(e1, A, Side.BUY, 5, "100.00", 1),  // one entry order, two partial fills
                fill(UUID.randomUUID(), A, Side.SELL, 10, "98.00", 5),                          // a loss of 20
                fill(e2, B, Side.SELL, 10, "50.00", 6), fill(UUID.randomUUID(), B, Side.BUY, 10, "49.00", 9)); // a gain of 10
        AccountSnapshotBuilder.Day day = AccountSnapshotBuilder.day(trades);
        assertThat(day.entries()).hasSize(2).containsExactly(Instant.parse("2026-09-08T04:00:00Z"), Instant.parse("2026-09-08T04:06:00Z"));
        assertThat(day.closes()).extracting(c -> c.realized().intValue()).containsExactly(-20, 10);
        assertThat(day.consecutiveLosses()).isZero();
        assertThat(AccountSnapshotBuilder.consecutiveLosses(trades.subList(0, 3))).isEqualTo(1);
    }
}
