package money.hejje.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;

/** An order Hejje owns and tracks against the broker. */
public record HejjeOrder(
        UUID id,
        UUID intentId,
        ExecutionMode mode,
        String broker,
        String brokerOrderId,
        String tag,
        UUID instrumentId,
        Side side,
        int quantity,
        int filledQuantity,
        BigDecimal averagePrice,
        OrderType orderType,
        Product product,
        BigDecimal limitPrice,
        BigDecimal triggerPrice,
        OrderState state,
        String lastBrokerStatus,
        Instant placedAt,
        Instant updatedAt,
        UUID parentOrderId,
        OrderRole role) {

    public int pendingQuantity() {
        return quantity - filledQuantity;
    }
}
