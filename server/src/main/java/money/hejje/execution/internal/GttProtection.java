package money.hejje.execution.internal;

import money.hejje.execution.GttService;
import money.hejje.orders.OrderFilledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** After a delivery order fills, its position's GTT is placed, resized or cancelled within seconds (plan M11.2). */
@Component
class GttProtection {

    private static final Logger log = LoggerFactory.getLogger(GttProtection.class);

    private final GttService gtts;
    private final ExecutorLease lease;

    GttProtection(GttService gtts, ExecutorLease lease) {
        this.gtts = gtts;
        this.lease = lease;
    }

    @ApplicationModuleListener
    void onFilled(OrderFilledEvent event) {
        if (!lease.isActive()) {
            return; // the active executor protects the position
        }
        try {
            gtts.onDeliveryFill(event.orderId());
        } catch (RuntimeException e) {
            log.warn("GTT protection after the fill of order {} failed: {}", event.orderId(), e.getMessage());
        }
    }
}
