package money.hejje.signals.internal;

import money.hejje.orders.OrderFilledEvent;
import money.hejje.orders.OrderStateChangedEvent;
import money.hejje.signals.SignalEngine;
import money.hejje.strategy.DeploymentChanged;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Durable order and deployment events into the engine. Listeners are idempotent (positions are looked up by order id). */
@Component
class OrderEventBridge {

    private final SignalEngine engine;
    private final SignalMetrics metrics;

    OrderEventBridge(SignalEngine engine, SignalMetrics metrics) {
        this.engine = engine;
        this.metrics = metrics;
    }

    @ApplicationModuleListener
    void onFilled(OrderFilledEvent event) {
        metrics.onFilled(event.orderId(), event.occurredAt());
        engine.onOrderFilled(event.orderId(), event.filledQuantity(), event.averagePrice(), event.complete());
    }

    @ApplicationModuleListener
    void onStateChanged(OrderStateChangedEvent event) {
        metrics.onStateChanged(event.orderId(), event.to(), event.occurredAt());
        engine.onOrderStateChanged(event.orderId(), event.to());
    }

    @ApplicationModuleListener
    void onDeploymentChanged(DeploymentChanged event) {
        if (engine.isRunning()) {
            engine.refresh();
        }
    }
}
