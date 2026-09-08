package money.hejje.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Side;

/** One execution (fill) recorded from the broker. */
public record Trade(UUID id, UUID orderId, String brokerTradeId, UUID instrumentId, Side side, int quantity, BigDecimal price,
        Instant ts, ExecutionMode mode, UUID strategyId) {
}
