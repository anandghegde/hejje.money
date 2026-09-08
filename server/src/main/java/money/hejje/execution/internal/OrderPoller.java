package money.hejje.execution.internal;

import java.time.Duration;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerSessionState;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderEventSource;
import money.hejje.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Poll fallback: every 5 s, reconcile non-terminal orders older than 10 s from the broker (in case an update was missed). */
@Component
public class OrderPoller {

    private static final Logger log = LoggerFactory.getLogger(OrderPoller.class);
    private static final Duration MIN_AGE = Duration.ofSeconds(10);

    private final BrokerAdapter broker;
    private final OrderService orders;
    private final HejjeProperties properties;
    private final HejjeClock clock;

    OrderPoller(BrokerAdapter broker, OrderService orders, HejjeProperties properties, HejjeClock clock) {
        this.broker = broker;
        this.orders = orders;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 5000)
    void poll() {
        if (broker.sessionState() != BrokerSessionState.CONNECTED) {
            return;
        }
        for (HejjeOrder order : orders.live(properties.mode())) {
            if (order.brokerOrderId() == null || clock.now().isBefore(order.updatedAt().plus(MIN_AGE))) {
                continue;
            }
            try {
                BrokerOrder brokerOrder = broker.getOrder(new BrokerOrderRef(order.brokerOrderId()));
                orders.applyBrokerUpdate(broker.brokerCode(), properties.mode(), brokerOrder, OrderEventSource.BROKER_POLL);
            } catch (BrokerException e) {
                log.debug("Poll of order {} failed ({}): {}", order.id(), e.kind(), e.brokerMessage());
            }
        }
    }
}
