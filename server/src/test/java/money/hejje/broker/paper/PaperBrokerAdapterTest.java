package money.hejje.broker.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.broker.BrokerOrderUpdate;
import money.hejje.broker.BrokerOrderUpdates;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.Validity;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaperBrokerAdapterTest {

    static final UUID INFY = UUID.randomUUID();
    static final BrokerInstrumentResolver RESOLVER = new BrokerInstrumentResolver() {
        public Optional<BrokerInstrumentRef> forInstrument(UUID id, String broker) {
            return Optional.of(new BrokerInstrumentRef(id, Exchange.NSE, InstrumentType.EQ, "408065", "INFY", "NSE", 1, new BigDecimal("0.05")));
        }
        public Optional<UUID> byBrokerToken(String broker, String token) { return Optional.of(INFY); }
        public Optional<UUID> byTradingSymbol(String broker, String seg, String sym) { return Optional.of(INFY); }
    };

    BrokerAdapter delegate;
    BrokerOrderUpdates updates;
    List<BrokerOrderUpdate> received;
    PaperBrokerAdapter paper;

    @BeforeEach
    void setUp() {
        delegate = Mockito.mock(BrokerAdapter.class);
        Mockito.when(delegate.brokerCode()).thenReturn("zerodha");
        updates = new BrokerOrderUpdates();
        received = new CopyOnWriteArrayList<>();
        updates.subscribe(received::add);
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);
        paper = new PaperBrokerAdapter(delegate, RESOLVER, updates, clock, new PaperBrokerProperties(new BigDecimal("5"), 0.0, 1_000_000));
    }

    BrokerOrderRequest order(Side side, int qty, OrderType type, String limit, String trigger) {
        return new BrokerOrderRequest(INFY, side, Quantity.of(qty), type, Product.MIS, limit == null ? null : Price.of(limit),
                trigger == null ? null : Price.of(trigger), Validity.DAY, "t");
    }

    @Test
    void marketFillsAtLastPriceWithSlippageAndNeverCallsTheBroker() {
        paper.injectTick(new MarketTick(INFY, Instant.now(), new BigDecimal("1000.00"), null, null, 0, 0, MarketTick.Mode.FULL));
        BrokerOrderRef ref = paper.placeOrder(order(Side.BUY, 10, OrderType.MARKET, null, null));
        paper.flush();
        var filled = paper.getOrder(ref);
        assertThat(filled.status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        // 5 bps slippage on a buy: 1000 * 1.0005 = 1000.50
        assertThat(filled.averagePrice()).isEqualByComparingTo("1000.50");
        assertThat(paper.getPositions()).singleElement().satisfies(p -> assertThat(p.netQuantity()).isEqualTo(10));
        verify(delegate, never()).placeOrder(Mockito.any());
    }

    @Test
    void limitFillsOnlyAfterCrossingTick() {
        paper.injectTick(new MarketTick(INFY, Instant.now(), new BigDecimal("1000.00"), null, null, 0, 0, MarketTick.Mode.FULL));
        BrokerOrderRef ref = paper.placeOrder(order(Side.BUY, 5, OrderType.LIMIT, "995.00", null));
        assertThat(paper.getOrder(ref).status()).isEqualTo(BrokerOrderStatus.OPEN);
        paper.injectTick(new MarketTick(INFY, Instant.now(), new BigDecimal("996.00"), null, null, 0, 0, MarketTick.Mode.FULL));
        assertThat(paper.getOrder(ref).status()).isEqualTo(BrokerOrderStatus.OPEN);
        paper.injectTick(new MarketTick(INFY, Instant.now(), new BigDecimal("994.50"), null, null, 0, 0, MarketTick.Mode.FULL));
        assertThat(paper.getOrder(ref).status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        assertThat(paper.getOrder(ref).averagePrice()).isEqualByComparingTo("995.00");
    }

    @Test
    void stopTriggersOnBothSides() {
        paper.injectTick(new MarketTick(INFY, Instant.now(), new BigDecimal("1000.00"), null, null, 0, 0, MarketTick.Mode.FULL));
        BrokerOrderRef sell = paper.placeOrder(order(Side.SELL, 5, OrderType.SL_M, null, "990.00"));
        BrokerOrderRef buy = paper.placeOrder(order(Side.BUY, 5, OrderType.SL, "1011.00", "1010.00"));
        assertThat(paper.getOrder(sell).status()).isEqualTo(BrokerOrderStatus.TRIGGER_PENDING);
        paper.injectTick(new MarketTick(INFY, Instant.now(), new BigDecimal("989.00"), null, null, 0, 0, MarketTick.Mode.FULL));
        assertThat(paper.getOrder(sell).status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        paper.injectTick(new MarketTick(INFY, Instant.now(), new BigDecimal("1010.50"), null, null, 0, 0, MarketTick.Mode.FULL));
        assertThat(paper.getOrder(buy).status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        assertThat(paper.getOrder(buy).averagePrice()).isEqualByComparingTo("1011.00");
    }
    @Test
    void instrumentBrokerCodeIsTheDelegates() {
        Mockito.when(delegate.instrumentBrokerCode()).thenReturn("zerodha");
        assertThat(paper.brokerCode()).isEqualTo("paper");
        assertThat(paper.instrumentBrokerCode()).isEqualTo("zerodha");
    }
}
