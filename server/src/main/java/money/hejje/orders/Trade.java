package money.hejje.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Product;
import money.hejje.common.Side;

/**
 * One execution (fill) recorded from the broker. {@code product} is the order's product (null when not known), which
 * separates the swing book (CNC) from intraday (plan M11.1).
 */
public record Trade(UUID id, UUID orderId, String brokerTradeId, UUID instrumentId, Side side, int quantity, BigDecimal price,
        Instant ts, ExecutionMode mode, UUID strategyId, Product product) {

    public Trade(UUID id, UUID orderId, String brokerTradeId, UUID instrumentId, Side side, int quantity, BigDecimal price, Instant ts,
            ExecutionMode mode, UUID strategyId) {
        this(id, orderId, brokerTradeId, instrumentId, side, quantity, price, ts, mode, strategyId, null);
    }

    /** A delivery (CNC) fill: part of the swing book, not of the intraday one. */
    public boolean isDelivery() {
        return product == Product.CNC;
    }
}
