package money.hejje.execution;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.orders.OrderReason;

/** A request to execute a basket; {@code clientId} + {@code idempotencyKey} make it idempotent. */
public record BasketCommand(UUID clientId, String idempotencyKey, ActorType source, String actorId, String name, Basket.Policy policy, Basket.Rollback rollback,
        Duration deadline, UUID strategyId, OrderReason reason, List<Leg> legs) {

    public BasketCommand {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    public record Leg(UUID instrumentId, Side side, int quantity, OrderType orderType, Product product, Price limitPrice, Price triggerPrice, Price stopPrice,
            Price targetPrice, boolean hedgeFirst) {
    }
}
