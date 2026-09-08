package money.hejje.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import money.hejje.AbstractIntegrationTest;
import money.hejje.broker.BrokerException;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.internal.UnknownOrderResolver;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.orders.Position;
import money.hejje.orders.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExecutionIT extends AbstractIntegrationTest {

    @Autowired ExecutionEngine engine;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired UnknownOrderResolver unknownResolver;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    UUID infy;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        clock.setIst("2026-09-08T10:00:00"); // trading session open
    }

    OrderIntentCommand market(Side side, int qty, OrderReason reason) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, infy, side,
                Quantity.of(qty), OrderType.MARKET, Product.MIS, null, null, null, null, null, reason);
    }

    @Test
    void marketOrderFillsAndBuildsPosition() {
        fake.injectQuote(infy, "1500.00");
        HejjeOrder order = engine.submit(market(Side.BUY, 10, OrderReason.MANUAL));
        fake.flush();
        HejjeOrder filled = orders.findById(order.id()).orElseThrow();
        assertThat(filled.state()).isEqualTo(OrderState.FILLED);
        assertThat(filled.filledQuantity()).isEqualTo(10);
        assertThat(filled.averagePrice()).isEqualByComparingTo("1500.00");
        assertThat(orders.tradesForOrder(order.id())).hasSize(1);
        Position position = orders.positions(money.hejje.common.ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(infy)).findFirst().orElseThrow();
        assertThat(position.netQuantity()).isEqualTo(10);
        assertThat(position.averagePrice()).isEqualByComparingTo("1500.00");
    }

    @Test
    void threeFillsProduceCorrectAverageAndRealizedPnl() {
        fake.injectQuote(infy, "100.00");
        engine.submit(market(Side.BUY, 10, OrderReason.MANUAL));
        fake.flush();
        fake.injectQuote(infy, "110.00");
        engine.submit(market(Side.BUY, 10, OrderReason.MANUAL));
        fake.flush();
        fake.injectQuote(infy, "120.00");
        engine.submit(market(Side.SELL, 10, OrderReason.POSITION_CLOSE));
        fake.flush();

        Position position = orders.positions(money.hejje.common.ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(infy)).findFirst().orElseThrow();
        assertThat(position.netQuantity()).isEqualTo(10);
        assertThat(position.averagePrice()).isEqualByComparingTo("105.00");
        assertThat(position.realizedPnl()).isEqualTo(Money.of("150.00")); // (120-105)*10
        assertThat(orders.trades(money.hejje.common.ExecutionMode.PAPER, null, null)).hasSize(3);
    }

    @Test
    void partialThenFullFill() {
        fake.injectQuote(infy, "100.00");
        HejjeOrder order = engine.submit(new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test",
                null, null, infy, Side.BUY, Quantity.of(10), OrderType.LIMIT, Product.MIS, Price.of("95.00"), null, null, null, null,
                OrderReason.MANUAL));
        fake.flush();
        HejjeOrder resting = orders.findById(order.id()).orElseThrow();
        assertThat(resting.state()).isIn(OrderState.OPEN, OrderState.BROKER_ACCEPTED);
        fake.partialFill(resting.brokerOrderId(), 4, new BigDecimal("95.00"));
        fake.flush();
        assertThat(orders.findById(order.id()).orElseThrow().state()).isEqualTo(OrderState.PARTIALLY_FILLED);
        assertThat(orders.findById(order.id()).orElseThrow().filledQuantity()).isEqualTo(4);
        fake.partialFill(resting.brokerOrderId(), 6, new BigDecimal("95.00"));
        fake.flush();
        HejjeOrder done = orders.findById(order.id()).orElseThrow();
        assertThat(done.state()).isEqualTo(OrderState.FILLED);
        assertThat(done.filledQuantity()).isEqualTo(10);
        assertThat(orders.tradesForOrder(order.id())).hasSize(2);
    }

    @Test
    void droppedAckGoesUnknownThenPollResolvesToOpen() {
        fake.injectQuote(infy, "1500.00");
        fake.dropAck();
        HejjeOrder order = engine.submit(new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test",
                null, null, infy, Side.BUY, Quantity.of(10), OrderType.LIMIT, Product.MIS, Price.of("1400.00"), null, null, null, null,
                OrderReason.MANUAL));
        fake.flush();
        assertThat(orders.findById(order.id()).orElseThrow().state()).isEqualTo(OrderState.UNKNOWN);
        boolean resolved = unknownResolver.resolveNow(order.id());
        fake.flush();
        assertThat(resolved).isTrue();
        assertThat(orders.findById(order.id()).orElseThrow().state()).isIn(OrderState.OPEN, OrderState.BROKER_ACCEPTED);
    }

    @Test
    void validationFailureReturns422AndNeverCallsBroker() {
        // limit price off the tick size (INFY tick 0.05)
        OrderIntentCommand bad = new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null,
                null, infy, Side.BUY, Quantity.of(10), OrderType.LIMIT, Product.MIS, Price.of("1500.03"), null, null, null, null,
                OrderReason.MANUAL);
        assertThatThrownBy(() -> engine.submit(bad)).isInstanceOfSatisfying(ExecutionException.Validation.class,
                e -> assertThat(e.reasons()).anyMatch(r -> r.contains("tick size")));
        assertThat(fake.getOrders()).isEmpty();
    }

    @Test
    void idempotentReplayReturnsSameOrder() {
        fake.injectQuote(infy, "1500.00");
        OrderIntentCommand command = market(Side.BUY, 10, OrderReason.MANUAL);
        HejjeOrder first = engine.submit(command);
        HejjeOrder replay = engine.submit(command);
        fake.flush();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(orders.query(money.hejje.common.ExecutionMode.PAPER, null, null, null).stream()
                .filter(o -> o.instrumentId().equals(infy)).count()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateSubmitCreatesOneOrder() throws Exception {
        fake.injectQuote(infy, "1500.00");
        OrderIntentCommand command = market(Side.BUY, 10, OrderReason.MANUAL);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<UUID> a = new AtomicReference<>();
        AtomicReference<UUID> b = new AtomicReference<>();
        Runnable task = () -> {
            try {
                start.await();
                HejjeOrder o = engine.submit(command);
                (a.get() == null ? a : b).set(o.id());
            } catch (ExecutionException.IdempotencyInFlight ignored) {
            } catch (Exception ignored) {
            }
        };
        pool.submit(task);
        pool.submit(task);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        fake.flush();
        long created = orders.query(money.hejje.common.ExecutionMode.PAPER, null, null, null).stream()
                .filter(o -> o.instrumentId().equals(infy)).count();
        assertThat(created).isEqualTo(1);
    }

    @Test
    void cancelFlow() {
        fake.injectQuote(infy, "1500.00");
        HejjeOrder order = engine.submit(new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test",
                null, null, infy, Side.BUY, Quantity.of(10), OrderType.LIMIT, Product.MIS, Price.of("1400.00"), null, null, null, null,
                OrderReason.MANUAL));
        fake.flush();
        engine.cancel(order.id());
        fake.flush();
        assertThat(orders.findById(order.id()).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);
    }
}
