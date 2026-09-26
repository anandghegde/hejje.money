package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.analytics.AnalyticsService;
import money.hejje.analytics.Horizon;
import money.hejje.analytics.TradeReview;
import money.hejje.broker.BrokerHolding;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.execution.ReconciliationIssue;
import money.hejje.execution.ReconciliationService;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.risk.RiskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan M11.1 acceptance: a PAPER delivery position opened on day 1 is still open after a restart on day 2, is not
 * force-exited, reconciles against the simulated holdings (T1 on day 2), and closes on day 3 with delivery costs (DP
 * charge on the sell day) and a 2-session holding period.
 */
class SwingPositionIT extends AbstractIntegrationTest {

    static final String DAY1 = "2026-12-01";
    static final String DAY2 = "2026-12-02";
    static final String DAY3 = "2026-12-03";

    @Autowired ExecutionEngine engine;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired SwingService swing;
    @Autowired ReconciliationService reconciliation;
    @Autowired RiskService risk;
    @Autowired AnalyticsService analytics;
    @Autowired ApplicationContext context;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;

    UUID infy;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record, swing_position, trade_review CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE WHERE mode = 'PAPER'");
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear(); // other suites leave newer-stamped quotes behind; the cache ignores older ticks
        SwingRiskIT.defaultSwingLimits(jdbc);
    }

    @AfterEach
    void tearDown() {
        quoteCache.clear(); // and this suite's December ticks must not shadow later suites' earlier ones
        clock.set(java.time.Instant.now());
    }

    OrderIntentCommand deliveryBuy(int qty, String stop, String goal) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, infy, Side.BUY,
                Quantity.of(qty), OrderType.MARKET, Product.CNC, null, null, Price.of(stop), Price.of(goal), null, OrderReason.MANUAL);
    }

    Position deliveryPosition() {
        return orders.positions(ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(infy) && p.product() == Product.CNC).findFirst().orElseThrow();
    }

    List<ReconciliationIssue> openIssues(String kind) {
        return reconciliation.openIssues().stream().filter(i -> kind.equals(i.kind())).toList();
    }

    void restart() throws Exception {
        // the startup recovery a restarted server runs: reconcile orders, positions and holdings, restore in-flight orders
        Object bootstrap = context.getBean("executorBootstrap");
        java.lang.reflect.Method runNow = bootstrap.getClass().getMethod("runNow");
        runNow.setAccessible(true);
        runNow.invoke(bootstrap);
    }

    @Test
    void aDeliveryPositionCarriesOverTwoNightsReconcilesAndClosesWithDeliveryCosts() throws Exception {
        // day 1: buy 10 for delivery with a stop and a goal
        clock.setIst(DAY1 + "T10:00:00");
        fake.injectQuote(infy, "100.00");
        engine.submit(deliveryBuy(10, "93.00", "120.00"));
        fake.flush();
        awaitAsyncListeners();

        SwingPosition open = swing.open(ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(infy)).findFirst().orElseThrow();
        assertThat(open.entryDate()).isEqualTo(LocalDate.parse(DAY1));
        assertThat(open.quantity()).isEqualTo(10);
        assertThat(open.initialStop()).isEqualByComparingTo("93.00");
        assertThat(open.goal()).isEqualByComparingTo("120.00");
        // the swing book is not the intraday one: the intraday dashboard counts no open position
        assertThat(risk.dashboard(ExecutionMode.PAPER).openPositions()).isZero();

        // after the close: still open (nothing force-exits a delivery position), and today's CNC position matches the book
        clock.setIst(DAY1 + "T15:45:00");
        assertThat(swing.reconcile()).isEmpty();
        assertThat(deliveryPosition().netQuantity()).isEqualTo(10);

        // day 2: a restart; the broker now reports the shares as a T1 holding and no delivery position
        clock.setIst(DAY2 + "T09:00:00");
        restart();
        assertThat(fake.getPositions()).noneMatch(p -> p.product() == Product.CNC);
        assertThat(fake.getHoldings()).singleElement().satisfies(h -> {
            assertThat(h.quantity()).isZero();
            assertThat(h.t1Quantity()).isEqualTo(10);
        });
        assertThat(openIssues("POSITION_MISMATCH")).isEmpty();
        assertThat(openIssues(ReconciliationService.HOLDINGS_MISMATCH)).isEmpty();
        assertThat(risk.killSwitch(ExecutionMode.PAPER).stopNewOrders()).isFalse();
        assertThat(deliveryPosition().netQuantity()).isEqualTo(10);

        String token = adminAccessToken();
        pipeline.onTick(new money.hejje.common.event.MarketTick(infy, clock.instant(), new BigDecimal("107.00"), null, null, 0, 0,
                money.hejje.common.event.MarketTick.Mode.LTP));
        ResponseEntity<List> book = rest.exchange("/api/v1/swing/positions", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(book.getBody()).singleElement().satisfies(row -> {
            Map<String, Object> r = (Map<String, Object>) row;
            assertThat(r.get("entryDate")).isEqualTo(DAY1);
            assertThat(r.get("daysHeld")).isEqualTo(1);
            assertThat(new BigDecimal(String.valueOf(r.get("lastPrice")))).isEqualByComparingTo("107.00");
            assertThat(new BigDecimal(String.valueOf(r.get("r")))).isEqualByComparingTo("1.00"); // (107 - 100) / (100 - 93)
            assertThat(((Map<String, Object>) r.get("unrealizedPnl")).get("paise")).isEqualTo(7000);
            assertThat(r.get("gtt")).isEqualTo("ACTIVE");
            assertThat(r.get("symbol")).isEqualTo("NSE:INFY");
        });
        clock.setIst(DAY2 + "T15:45:00");
        assertThat(swing.reconcile()).isEmpty();

        // day 3: settled holding; sell it
        clock.setIst(DAY3 + "T10:00:00");
        assertThat(fake.getHoldings()).singleElement().satisfies(h -> assertThat(h.quantity()).isEqualTo(10));
        fake.injectQuote(infy, "110.00");
        engine.closePosition(infy, Product.CNC, null);
        fake.flush();
        awaitAsyncListeners();

        Position closed = deliveryPosition();
        assertThat(closed.netQuantity()).isZero();
        assertThat(closed.realizedPnl()).isEqualTo(Money.of("100.00"));
        // delivery costs: buy 1.19 (STT 1.00, txn 0.03, GST 0.01, stamp 0.15); sell 16.48 (STT 1.10, txn 0.03, GST 0.01, DP 15.34)
        assertThat(closed.fees()).isEqualTo(Money.of("17.67"));

        SwingPosition done = swing.closed(ExecutionMode.PAPER, 10).stream().filter(p -> p.id().equals(open.id())).findFirst().orElseThrow();
        assertThat(done.holdingDays()).isEqualTo(2);
        assertThat(done.exitPrice()).isEqualByComparingTo("110.00");
        assertThat(done.exitDate()).isEqualTo(LocalDate.parse(DAY3));
        assertThat(swing.open(ExecutionMode.PAPER)).isEmpty();

        TradeReview review = analytics.reviews(ExecutionMode.PAPER, 10).stream().filter(r -> r.instrumentId().equals(infy)).findFirst().orElseThrow();
        assertThat(review.horizon()).isEqualTo(Horizon.SWING);
        assertThat(review.holdingDays()).isEqualTo(2);
        assertThat(review.outcomeR()).isCloseTo((100.00 - 17.67) / 70.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(analytics.pnl("horizon", ExecutionMode.PAPER, clock.instant().minus(java.time.Duration.ofDays(5)), clock.instant()))
                .singleElement().satisfies(b -> assertThat(b.key()).isEqualTo("SWING"));

        // the sale shows as a negative delivery position while the holding still lists the shares: the book reconciles
        assertThat(swing.reconcile()).isEmpty();
    }

    @Test
    void aHoldingTheSwingBookDoesNotKnowIsAReconciliationIssue() {
        clock.setIst(DAY1 + "T10:00:00");
        fake.injectQuote(infy, "100.00");
        engine.submit(deliveryBuy(10, "93.00", "120.00"));
        fake.flush();

        clock.setIst(DAY2 + "T09:00:00");
        fake.seedHolding(infy, 5, new BigDecimal("98.00"), LocalDate.parse("2026-11-20"));
        List<ReconciliationIssue> issues = swing.reconcile();
        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(ReconciliationService.HOLDINGS_MISMATCH);
            assertThat(i.expected()).isEqualTo("10");
            assertThat(i.observed()).isEqualTo("15");
            assertThat(i.instrumentId()).isEqualTo(infy);
        });
        // a WARN: the intraday kill switch is not tripped by the swing book
        assertThat(risk.killSwitch(ExecutionMode.PAPER).stopNewOrders()).isFalse();
        List<BrokerHolding> holdings = fake.getHoldings();
        assertThat(holdings).singleElement().satisfies(h -> assertThat(h.totalQuantity()).isEqualTo(15));
    }
}
