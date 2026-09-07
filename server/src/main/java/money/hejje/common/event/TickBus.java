package money.hejje.common.event;

import java.util.function.Consumer;

/**
 * In-process, non-durable fan-out for {@link MarketEvent}s. Listeners run on the publisher's thread and
 * must return quickly. The implementation arrives in M1.3; this is the contract other modules code against.
 */
public interface TickBus {

    void publish(MarketEvent event);

    /** Registers a listener; closing the returned handle unsubscribes it. */
    AutoCloseable subscribe(Consumer<? super MarketEvent> listener);
}
