package money.hejje.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.orders.OrderReason;

/**
 * A parent intent worked as child orders (plan M5.3). Children are normal orders whose {@code parentOrderId} is this id.
 *
 * @param referencePrice the price the tolerance is measured from (limit price, else the last price at the start)
 */
public record SplitOrder(UUID id, ExecutionMode mode, UUID clientId, ActorType source, String actorId, UUID strategyId, UUID instrumentId, Side side,
        int quantity, OrderType orderType, Product product, BigDecimal limitPrice, BigDecimal triggerPrice, BigDecimal stopPrice, BigDecimal targetPrice,
        OrderReason reason, SplitPolicy policy, Status status, int filledQuantity, int children, BigDecimal referencePrice, Instant deadline, String detail,
        Instant createdAt, Instant updatedAt) {

    public enum Status { WORKING, COMPLETED, CANCELLED, EXPIRED, FAILED }
}
