package money.hejje.market.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.market.MarketProperties;
import money.hejje.market.MarketService;
import money.hejje.orders.OrderIntentCreatedEvent;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import org.junit.jupiter.api.Test;

class TradedInstrumentsTest {

    private final MarketService market = mock(MarketService.class);
    private final OrderService orders = mock(OrderService.class);
    private final HejjeProperties hejje = new HejjeProperties(ExecutionMode.PAPER, ZoneId.of("Asia/Kolkata"), Path.of("."));
    private final HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-24T10:00:00"), MutableClock.IST, (d, e) -> false);

    private TradedInstruments traded(boolean streaming) {
        MarketProperties props = new MarketProperties(List.of(), streaming, Duration.ofSeconds(10), Duration.ofSeconds(5), 1000, false, 15, 3, false);
        return new TradedInstruments(market, orders, hejje, props);
    }

    @Test
    void anOrderIntentStreamsItsInstrument() {
        UUID instrument = UUID.randomUUID();
        traded(true).onIntent(new OrderIntentCreatedEvent(EventMeta.create(clock), UUID.randomUUID(), instrument));
        verify(market).subscribe(Set.of(instrument));
    }

    @Test
    void openPositionsAreStreamedAfterARestart() {
        UUID instrument = UUID.randomUUID();
        when(orders.openPositions(ExecutionMode.PAPER)).thenReturn(List.of(new Position(UUID.randomUUID(), ExecutionMode.PAPER, instrument,
                Product.MIS, null, 5, new BigDecimal("1500.00"), Money.ZERO, Money.ZERO, 5, 0, Instant.now(), Instant.now())));
        traded(true).subscribeOpenPositions();
        verify(market).subscribe(Set.of(instrument));
    }

    @Test
    void nothingIsStreamedWhereStreamingIsOff() {
        when(orders.openPositions(any())).thenReturn(List.of());
        TradedInstruments off = traded(false);
        off.onIntent(new OrderIntentCreatedEvent(EventMeta.create(clock), UUID.randomUUID(), UUID.randomUUID()));
        off.subscribeOpenPositions();
        verify(market, never()).subscribe(any());
    }
}
