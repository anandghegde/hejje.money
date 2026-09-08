package money.hejje.execution;

import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.orders.OrderReason;

/**
 * A request to submit an order (PRD section 30). {@code clientId} and {@code idempotencyKey} identify the caller and the
 * request for idempotent replay; the rest is the intent.
 */
public record OrderIntentCommand(
        UUID clientId,
        String idempotencyKey,
        ActorType source,
        String actorId,
        UUID strategyId,
        UUID signalId,
        UUID instrumentId,
        Side side,
        Quantity quantity,
        OrderType orderType,
        Product product,
        Price limitPrice,
        Price triggerPrice,
        Price stopPrice,
        Price targetPrice,
        Money maxRisk,
        OrderReason reason) {
}
