package money.hejje.orders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.Money;

/** A persisted order intent (PRD section 30). Prices and risk are Hejje value types. */
public record OrderIntent(
        UUID id,
        String idempotencyKey,
        UUID clientId,
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
        OrderReason reason,
        ExecutionMode mode,
        IntentStatus status,
        List<String> validationErrors,
        Instant createdAt) {

    public OrderIntent withStatus(IntentStatus status, List<String> validationErrors) {
        return new OrderIntent(id, idempotencyKey, clientId, source, actorId, strategyId, signalId, instrumentId, side, quantity,
                orderType, product, limitPrice, triggerPrice, stopPrice, targetPrice, maxRisk, reason, mode, status,
                validationErrors == null ? List.of() : List.copyOf(validationErrors), createdAt);
    }

    /** True when this intent reduces or closes exposure (risk limit checks are relaxed for these). */
    public boolean isExposureReducing() {
        return reason == OrderReason.POSITION_CLOSE || reason == OrderReason.STRATEGY_EXIT || reason == OrderReason.STRATEGY_STOP
                || reason == OrderReason.KILL_SWITCH || reason == OrderReason.BASKET_ROLLBACK;
    }
}
