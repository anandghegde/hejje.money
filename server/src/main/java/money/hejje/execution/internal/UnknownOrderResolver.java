package money.hejje.execution.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerOrder;
import money.hejje.common.config.HejjeProperties;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderEventSource;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves orders left in {@code UNKNOWN} after a timed-out or failed submit: polls the broker orderbook for a matching
 * tag for up to 30 s. If found, the order state is corrected from broker truth; otherwise it stays UNKNOWN for the M1.6
 * reconciliation service to handle.
 */
@Component
public class UnknownOrderResolver {

    private static final Logger log = LoggerFactory.getLogger(UnknownOrderResolver.class);
    private static final Duration WINDOW = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofSeconds(3);

    private final BrokerAdapter broker;
    private final OrderService orders;
    private final HejjeProperties properties;
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "unknown-order-resolver");
        t.setDaemon(true);
        return t;
    });

    UnknownOrderResolver(BrokerAdapter broker, OrderService orders, HejjeProperties properties) {
        this.broker = broker;
        this.properties = properties;
        this.orders = orders;
    }

    public void scheduleResolve(UUID orderId) {
        scheduleResolve(orderId, 0);
    }

    private void scheduleResolve(UUID orderId, long elapsedMs) {
        scheduler.schedule(() -> attempt(orderId, elapsedMs), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Runs one resolution attempt synchronously (tests). Returns true when the order was resolved out of UNKNOWN. */
    public boolean resolveNow(UUID orderId) {
        return tryResolve(orderId);
    }

    private void attempt(UUID orderId, long elapsedMs) {
        try {
            if (tryResolve(orderId) || elapsedMs + INTERVAL.toMillis() >= WINDOW.toMillis()) {
                if (!tryResolve(orderId) && elapsedMs > 0) {
                    log.warn("Order {} still UNKNOWN after {} ms; leaving for reconciliation", orderId, elapsedMs);
                }
                return;
            }
            scheduleResolve(orderId, elapsedMs + INTERVAL.toMillis());
        } catch (RuntimeException e) {
            log.warn("Unknown-order resolve for {} failed: {}", orderId, e.getMessage());
        }
    }

    private boolean tryResolve(UUID orderId) {
        HejjeOrder order = orders.findById(orderId).orElse(null);
        if (order == null || order.state() != OrderState.UNKNOWN) {
            return true;
        }
        try {
            Optional<BrokerOrder> match = broker.getOrders().stream().filter(o -> order.tag().equals(o.tag())).findFirst();
            if (match.isPresent()) {
                orders.applyBrokerUpdate(broker.brokerCode(), properties.mode(), match.get(), OrderEventSource.RECONCILIATION);
                return orders.findById(orderId).map(o -> o.state() != OrderState.UNKNOWN).orElse(true);
            }
        } catch (BrokerException e) {
            log.debug("Unknown-order poll failed ({}): {}", e.kind(), e.brokerMessage());
        }
        return false;
    }
}
