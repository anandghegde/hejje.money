package money.hejje.broker;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-process fan-out of {@link BrokerOrderUpdate}s. Every adapter (Zerodha ticker, postback, fake, paper) publishes
 * here; the execution module subscribes. Listeners run on the publishing thread and must not throw.
 */
@Component
public class BrokerOrderUpdates {

    private static final Logger log = LoggerFactory.getLogger(BrokerOrderUpdates.class);

    private final List<Consumer<BrokerOrderUpdate>> listeners = new CopyOnWriteArrayList<>();

    public AutoCloseable subscribe(Consumer<BrokerOrderUpdate> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void publish(BrokerOrderUpdate update) {
        for (Consumer<BrokerOrderUpdate> listener : listeners) {
            try {
                listener.accept(update);
            } catch (RuntimeException e) {
                log.error("Order update listener failed for broker order {}", update.order().brokerOrderId(), e);
            }
        }
    }
}
