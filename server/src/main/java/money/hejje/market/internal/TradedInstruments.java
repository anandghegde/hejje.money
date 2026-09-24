package money.hejje.market.internal;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import money.hejje.common.config.HejjeProperties;
import money.hejje.market.MarketProperties;
import money.hejje.market.MarketService;
import money.hejje.orders.OrderIntentCreatedEvent;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Streams every instrument Hejje trades, not only those of deployed strategies: an instrument is subscribed when an order
 * intent is created for it (manual orders, stops, closes) and, after a restart, when a position in it is still open.
 * Without ticks a paper stop never triggers and position P&L does not move. Only where streaming is on
 * ({@code hejje.market.stream-on-startup}); elsewhere (tests, e2e) instruments are streamed only on request.
 */
@Component
class TradedInstruments {

    private final MarketService market;
    private final OrderService orders;
    private final HejjeProperties properties;
    private final boolean enabled;

    TradedInstruments(MarketService market, OrderService orders, HejjeProperties properties, MarketProperties marketProperties) {
        this.market = market;
        this.orders = orders;
        this.properties = properties;
        this.enabled = marketProperties.streamOnStartup();
    }

    @EventListener
    void onIntent(OrderIntentCreatedEvent event) {
        if (enabled) {
            market.subscribe(Set.of(event.instrumentId()));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void subscribeOpenPositions() {
        if (!enabled) {
            return;
        }
        Set<UUID> open = orders.openPositions(properties.mode()).stream().map(Position::instrumentId).collect(Collectors.toSet());
        if (!open.isEmpty()) {
            market.subscribe(open);
        }
    }
}
