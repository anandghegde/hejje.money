package money.hejje.execution.internal;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.PlanningProperties;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderService;
import org.springframework.stereotype.Component;

/** Waits for an order placed by a basket or a split to reach a terminal state (plan M5.3). */
@Component
public class OrderWaiter {

    private final OrderService orders;
    private final HejjeClock clock;
    private final PlanningProperties planning;

    OrderWaiter(OrderService orders, HejjeClock clock, PlanningProperties planning) {
        this.orders = orders;
        this.clock = clock;
        this.planning = planning;
    }

    /** Polls until the order is terminal, the business-clock {@code deadline} passes or {@code legTimeout} of real time elapses; returns the latest state. */
    public HejjeOrder await(UUID orderId, Instant deadline) {
        long end = System.nanoTime() + planning.legTimeout().toNanos();
        while (true) {
            HejjeOrder order = orders.findById(orderId).orElseThrow();
            if (order.state().isTerminal() || clock.now().isAfter(deadline) || System.nanoTime() > end) {
                return order;
            }
            sleep(50);
        }
    }

    public static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
