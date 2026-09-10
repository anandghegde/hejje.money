package money.hejje.execution;

import java.math.BigDecimal;
import java.util.UUID;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;

/**
 * One leg of a basket.
 *
 * @param executionOrder the position in which the leg was placed (hedge legs first), null until placed
 */
public record BasketLeg(UUID id, int sequence, boolean hedgeFirst, Integer executionOrder, UUID instrumentId, Side side, int quantity, OrderType orderType,
        Product product, BigDecimal limitPrice, BigDecimal triggerPrice, BigDecimal stopPrice, BigDecimal targetPrice, UUID orderId, Status status, String detail,
        UUID rollbackOrderId) {

    public enum Status { PENDING, SUBMITTED, FILLED, FAILED, CANCELLED, SKIPPED, ROLLED_BACK }
}
