package money.hejje.broker.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerModifyRequest;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.broker.BrokerOrderUpdate;
import money.hejje.broker.BrokerOrderUpdates;
import money.hejje.broker.BrokerPosition;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.MarketDataStream;
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

class FakeBrokerAdapterTest {

    static final UUID INFY = UUID.randomUUID();
    static final BrokerInstrumentResolver RESOLVER = new BrokerInstrumentResolver() {
        @Override
        public Optional<BrokerInstrumentRef> forInstrument(UUID instrumentId, String broker) {
            return Optional.of(new BrokerInstrumentRef(instrumentId, Exchange.NSE, InstrumentType.EQ, "408065", "INFY", "NSE", 1, new BigDecimal("0.05")));
        }

        @Override
        public Optional<UUID> byBrokerToken(String broker, String brokerToken) {
            return Optional.of(INFY);
        }

        @Override
        public Optional<UUID> byTradingSymbol(String broker, String exchangeSegment, String tradingSymbol) {
            return Optional.of(INFY);
        }
    };

    BrokerOrderUpdates updates = new BrokerOrderUpdates();
    List<BrokerOrderUpdate> received = new CopyOnWriteArrayList<>();
    List<Object> events = new CopyOnWriteArrayList<>();
    FakeBrokerAdapter fake;

    @BeforeEach
    void setUp() {
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);
        fake = new FakeBrokerAdapter(new FakeBrokerProperties(true, 1_000_000), updates, RESOLVER, clock, events::add);
        updates.subscribe(received::add);
    }

    static BrokerOrderRequest order(Side side, int qty, OrderType type, String limit, String trigger) {
        return new BrokerOrderRequest(INFY, side, Quantity.of(qty), type, Product.MIS, limit == null ? null : Price.of(limit),
                trigger == null ? null : Price.of(trigger), Validity.DAY, "t1");
    }

    @Test
    void marketOrderFillsAtInjectedQuoteAndUpdatesPositionsAndFunds() {
        fake.injectQuote(INFY, "1500.00");
        BrokerOrderRef ref = fake.placeOrder(order(Side.BUY, 10, OrderType.MARKET, null, null));
        fake.flush();
        BrokerOrder order = fake.getOrder(ref);
        assertThat(order.status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        assertThat(order.filledQuantity()).isEqualTo(10);
        assertThat(order.averagePrice()).isEqualByComparingTo("1500.00");
        assertThat(order.tag()).isEqualTo("t1");
        assertThat(received).extracting(u -> u.order().status()).containsExactly(BrokerOrderStatus.OPEN, BrokerOrderStatus.COMPLETE);
        assertThat(fake.getTrades()).hasSize(1);
        BrokerPosition position = fake.getPositions().get(0);
        assertThat(position.netQuantity()).isEqualTo(10);
        assertThat(position.averagePrice()).isEqualByComparingTo("1500.00");
        assertThat(fake.getFunds().usedMargin().toRupeesString()).isEqualTo("3000.00");

        fake.injectQuote(INFY, "1510.00");
        assertThat(fake.getPositions().get(0).unrealizedPnl().toRupeesString()).isEqualTo("100.00");
        fake.placeOrder(order(Side.SELL, 10, OrderType.MARKET, null, null));
        fake.flush();
        position = fake.getPositions().get(0);
        assertThat(position.netQuantity()).isZero();
        assertThat(position.realizedPnl().toRupeesString()).isEqualTo("100.00");
        assertThat(fake.getFunds().availableCash().toRupeesString()).isEqualTo("1000100.00");
    }

    @Test
    void limitOrderRestsUntilQuoteCrosses() {
        fake.injectQuote(INFY, "1500.00");
        BrokerOrderRef ref = fake.placeOrder(order(Side.BUY, 5, OrderType.LIMIT, "1495.00", null));
        assertThat(fake.getOrder(ref).status()).isEqualTo(BrokerOrderStatus.OPEN);
        fake.injectQuote(INFY, "1497.00");
        assertThat(fake.getOrder(ref).status()).isEqualTo(BrokerOrderStatus.OPEN);
        fake.injectQuote(INFY, "1494.50");
        BrokerOrder filled = fake.getOrder(ref);
        assertThat(filled.status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        assertThat(filled.averagePrice()).isEqualByComparingTo("1495.00");
    }

    @Test
    void stopOrdersTriggerOnBothSides() {
        fake.injectQuote(INFY, "1500.00");
        BrokerOrderRef sellStop = fake.placeOrder(order(Side.SELL, 5, OrderType.SL_M, null, "1490.00"));
        BrokerOrderRef buyStop = fake.placeOrder(order(Side.BUY, 5, OrderType.SL, "1512.00", "1510.00"));
        assertThat(fake.getOrder(sellStop).status()).isEqualTo(BrokerOrderStatus.TRIGGER_PENDING);
        assertThat(fake.getOrder(buyStop).status()).isEqualTo(BrokerOrderStatus.TRIGGER_PENDING);
        fake.injectQuote(INFY, "1505.00");
        assertThat(fake.getOrder(sellStop).status()).isEqualTo(BrokerOrderStatus.TRIGGER_PENDING);
        fake.injectQuote(INFY, "1489.00");
        assertThat(fake.getOrder(sellStop).status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        assertThat(fake.getOrder(sellStop).averagePrice()).isEqualByComparingTo("1489.00");
        fake.injectQuote(INFY, "1511.00");
        BrokerOrder buy = fake.getOrder(buyStop);
        assertThat(buy.status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        assertThat(buy.averagePrice()).isEqualByComparingTo("1512.00");
    }

    @Test
    void modifyAndCancel() {
        fake.injectQuote(INFY, "1500.00");
        BrokerOrderRef ref = fake.placeOrder(order(Side.BUY, 5, OrderType.LIMIT, "1490.00", null));
        fake.modifyOrder(ref, new BrokerModifyRequest(Quantity.of(8), null, Price.of("1492.00"), null, null));
        BrokerOrder modified = fake.getOrder(ref);
        assertThat(modified.quantity()).isEqualTo(8);
        assertThat(modified.limitPrice()).isEqualByComparingTo("1492.00");
        fake.cancelOrder(ref);
        assertThat(fake.getOrder(ref).status()).isEqualTo(BrokerOrderStatus.CANCELLED);
        assertThatThrownBy(() -> fake.cancelOrder(ref)).isInstanceOfSatisfying(BrokerException.class,
                e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.REJECTED));
    }

    @Test
    void scriptedFailures() {
        fake.injectQuote(INFY, "1500.00");
        fake.failNext(BrokerException.Kind.REJECTED);
        assertThatThrownBy(() -> fake.placeOrder(order(Side.BUY, 1, OrderType.MARKET, null, null)))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.REJECTED));
        assertThat(fake.getOrders()).isEmpty();

        fake.failNext(BrokerException.Kind.TIMEOUT);
        assertThatThrownBy(() -> fake.placeOrder(order(Side.BUY, 1, OrderType.MARKET, null, null)))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BrokerException.Kind.TIMEOUT);
                    assertThat(e.retryable()).isTrue();
                });
        assertThat(fake.getOrders()).isEmpty();

        long start = System.nanoTime();
        fake.delayNext(150);
        fake.placeOrder(order(Side.BUY, 1, OrderType.MARKET, null, null));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(140);

        fake.dropAck();
        assertThatThrownBy(() -> fake.placeOrder(order(Side.BUY, 2, OrderType.MARKET, null, null)))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.TIMEOUT));
        assertThat(fake.getOrders()).hasSize(2); // the dropped-ack order reached the broker
        assertThat(fake.getOrders().get(1).quantity()).isEqualTo(2);
    }

    @Test
    void partialFillAndBrokerSideReject() {
        fake.injectQuote(INFY, "1500.00");
        BrokerOrderRef ref = fake.placeOrder(order(Side.BUY, 10, OrderType.LIMIT, "1400.00", null));
        fake.partialFill(ref.brokerOrderId(), 4, new BigDecimal("1400.00"));
        BrokerOrder partial = fake.getOrder(ref);
        assertThat(partial.status()).isEqualTo(BrokerOrderStatus.OPEN);
        assertThat(partial.filledQuantity()).isEqualTo(4);
        assertThat(partial.pendingQuantity()).isEqualTo(6);
        fake.rejectFromBroker(ref.brokerOrderId(), "RMS: margin");
        assertThat(fake.getOrder(ref).status()).isEqualTo(BrokerOrderStatus.REJECTED);
        assertThat(fake.getOrder(ref).statusMessage()).isEqualTo("RMS: margin");
    }

    @Test
    void sessionLifecycleAndStreaming() {
        assertThat(fake.sessionState()).isEqualTo(BrokerSessionState.CONNECTED);
        List<MarketTick> ticks = new CopyOnWriteArrayList<>();
        MarketDataStream stream = fake.streamMarketData(ticks::add);
        stream.subscribe(Set.of(INFY), MarketTick.Mode.FULL);
        fake.injectQuote(INFY, "1500.00");
        assertThat(ticks).singleElement().satisfies(t -> {
            assertThat(t.lastPrice()).isEqualByComparingTo("1500.00");
            assertThat(t.mode()).isEqualTo(MarketTick.Mode.FULL);
            assertThat(t.bid()).isEqualByComparingTo("1499.95");
        });
        stream.setMode(Set.of(INFY), MarketTick.Mode.LTP);
        fake.injectQuote(INFY, "1501.00");
        assertThat(ticks.get(1).bid()).isNull();
        stream.close();
        fake.injectQuote(INFY, "1502.00");
        assertThat(ticks).hasSize(2);

        fake.simulateSessionExpiry();
        assertThatThrownBy(fake::getProfile).isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.AUTH));
        assertThat(fake.sessionState()).isEqualTo(BrokerSessionState.DISCONNECTED);
        assertThat(events).hasSize(1);
        assertThatThrownBy(() -> fake.authenticate("bad")).isInstanceOf(BrokerException.class);
        assertThat(fake.authenticate("good").brokerUserId()).isEqualTo("FAKE001");
        assertThat(fake.sessionState()).isEqualTo(BrokerSessionState.CONNECTED);
        assertThat(fake.getProfile().brokerUserId()).isEqualTo("FAKE001");
    }
}
