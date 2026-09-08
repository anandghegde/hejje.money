package money.hejje.orders;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One transition in an order's history. */
public record OrderEvent(UUID id, UUID orderId, long seq, OrderState fromState, OrderState toState, OrderEventSource source,
        Map<String, Object> payload, Instant ts) {
}
