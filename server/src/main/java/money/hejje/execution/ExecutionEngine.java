package money.hejje.execution;

import java.util.UUID;
import money.hejje.common.Product;
import money.hejje.orders.HejjeOrder;

/**
 * Public API of the execution module: the only path to the broker. Every method is idempotent-safe where a client
 * supplies an {@code Idempotency-Key} (submit). Modify/cancel/close act on existing orders and positions.
 */
public interface ExecutionEngine {

    HejjeOrder submit(OrderIntentCommand command);

    HejjeOrder modify(UUID orderId, ModifyCommand command);

    HejjeOrder cancel(UUID orderId);

    int cancelAllOpen();

    HejjeOrder closePosition(UUID instrumentId, Product product, UUID strategyId);

    int closeAllPositions();
}
