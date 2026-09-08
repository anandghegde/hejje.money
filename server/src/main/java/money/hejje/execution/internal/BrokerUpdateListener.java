package money.hejje.execution.internal;

import jakarta.annotation.PostConstruct;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerOrderUpdate;
import money.hejje.broker.BrokerOrderUpdates;
import money.hejje.common.config.HejjeProperties;
import money.hejje.orders.OrderEventSource;
import money.hejje.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Turns broker order updates (WebSocket, postback, poll, simulation) into order-state changes and fills. */
@Component
public class BrokerUpdateListener {

    private static final Logger log = LoggerFactory.getLogger(BrokerUpdateListener.class);

    private final BrokerOrderUpdates updates;
    private final BrokerAdapter broker;
    private final OrderService orders;
    private final HejjeProperties properties;

    BrokerUpdateListener(BrokerOrderUpdates updates, BrokerAdapter broker, OrderService orders, HejjeProperties properties) {
        this.updates = updates;
        this.broker = broker;
        this.orders = orders;
        this.properties = properties;
    }

    @PostConstruct
    void subscribe() {
        updates.subscribe(this::onUpdate);
    }

    void onUpdate(BrokerOrderUpdate update) {
        try {
            orders.applyBrokerUpdate(broker.brokerCode(), properties.mode(), update.order(), source(update.source()));
        } catch (RuntimeException e) {
            log.warn("Failed to apply broker update for order {}: {}", update.order().brokerOrderId(), e.getMessage());
        }
    }

    private static OrderEventSource source(BrokerOrderUpdate.Source source) {
        return switch (source) {
            case BROKER_WS -> OrderEventSource.BROKER_WS;
            case BROKER_POSTBACK -> OrderEventSource.BROKER_POSTBACK;
            case BROKER_POLL -> OrderEventSource.BROKER_POLL;
        };
    }
}
