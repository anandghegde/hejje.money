package money.hejje.signals.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.orders.OrderState;
import org.springframework.stereotype.Component;

/** PRD 44 timers {@code signal.to.ack} (execute → broker acknowledgement) and {@code signal.to.fill} (execute → filled). */
@Component
public class SignalMetrics {

    private final Timer toAck;
    private final Timer toFill;
    private final Map<UUID, Instant> executedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> acked = new ConcurrentHashMap<>();

    SignalMetrics(MeterRegistry registry) {
        this.toAck = Timer.builder("signal.to.ack").description("Signal execute to broker acknowledgement").publishPercentiles(0.5, 0.95, 0.99).register(registry);
        this.toFill = Timer.builder("signal.to.fill").description("Signal execute to fill").publishPercentiles(0.5, 0.95, 0.99).register(registry);
    }

    public void executed(UUID orderId, Instant at) {
        executedAt.put(orderId, at);
    }

    void onStateChanged(UUID orderId, OrderState to, Instant at) {
        Instant start = executedAt.get(orderId);
        if (start == null || acked.containsKey(orderId)) {
            return;
        }
        if (to == OrderState.BROKER_ACCEPTED || to == OrderState.OPEN || to == OrderState.PARTIALLY_FILLED || to == OrderState.FILLED) {
            acked.put(orderId, true);
            toAck.record(Duration.between(start, at).abs());
        }
    }

    void onFilled(UUID orderId, Instant at) {
        Instant start = executedAt.remove(orderId);
        acked.remove(orderId);
        if (start != null) {
            toFill.record(Duration.between(start, at).abs());
        }
    }
}
