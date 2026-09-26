package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditRecord;
import money.hejje.audit.AuditService;
import money.hejje.broker.Gtt;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ExecutionException;
import money.hejje.execution.GttService;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.execution.PositionGtt;
import money.hejje.execution.ReconciliationIssue;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan M11.2 acceptance: every swing position is protected by an OCO GTT at the (simulated) broker that Hejje places after
 * the entry fills (sized to the filled quantity), moves tighten-only, cancels with the close and reconciles; a gap down
 * through the stop fills at the open; a position whose GTT disappears at the broker is flagged and blocks new swing entries.
 */
class SwingGttIT extends AbstractIntegrationTest {

    static final String DAY1 = "2026-12-08";
    static final String DAY2 = "2026-12-09";

    @Autowired ExecutionEngine engine;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired SwingService swing;
    @Autowired GttService gtts;
    @Autowired AuditService audit;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;

    UUID infy;
    UUID tcs;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record, swing_position, gtt, trade_review CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE WHERE mode = 'PAPER'");
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        SwingRiskIT.defaultSwingLimits(jdbc);
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear(); // other suites leave newer-stamped quotes behind; the cache ignores older ticks
        clock.setIst(DAY1 + "T10:00:00");
    }

    @AfterEach
    void tearDown() {
        quoteCache.clear(); // this suite's December ticks must not shadow later suites' earlier ones
        clock.set(java.time.Instant.now());
    }

    /** The price at the fake broker and a fresh tick in Hejje's quote cache at the clock's time. */
    void price(UUID instrument, String price) {
        fake.injectQuote(instrument, price);
        pipeline.onTick(new money.hejje.common.event.MarketTick(instrument, clock.instant(), new BigDecimal(price), null, null, 0, 0,
                money.hejje.common.event.MarketTick.Mode.LTP));
    }

    OrderIntentCommand deliveryBuy(UUID instrument, int qty, OrderType type, String limit, String stop, String goal) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, instrument, Side.BUY,
                Quantity.of(qty), type, Product.CNC, limit == null ? null : Price.of(limit), null, Price.of(stop), Price.of(goal), null, OrderReason.MANUAL);
    }

    Position position(UUID instrument) {
        return orders.positions(ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(instrument) && p.product() == Product.CNC).findFirst().orElseThrow();
    }

    Position enter(UUID instrument) throws InterruptedException {
        price(instrument, "100.00");
        engine.submit(deliveryBuy(instrument, 10, OrderType.MARKET, null, "93.00", "120.00"));
        fake.flush();
        awaitAsyncListeners();
        return position(instrument);
    }

    Gtt.Snapshot atBroker(String gttId) {
        return fake.getGtts().stream().filter(g -> g.id().equals(gttId)).findFirst().orElseThrow();
    }

    List<AuditRecord> audited(AuditEventType type, UUID positionId) {
        return audit.query(new AuditQuery(null, null, type, null, 0, 200)).content().stream()
                .filter(r -> positionId.toString().equals(r.payload().get("positionId"))).toList();
    }

    @Test
    void anEntryFillPlacesAnOcoGttSizedToTheFilledQuantity() throws Exception {
        price(infy, "100.00");
        HejjeOrder entry = engine.submit(deliveryBuy(infy, 10, OrderType.LIMIT, "99.00", "93.00", "120.00")); // rests below the market
        fake.flush();
        String brokerOrderId = orders.findById(entry.id()).orElseThrow().brokerOrderId();

        fake.partialFill(brokerOrderId, 4, new BigDecimal("99.00"));
        fake.flush();
        awaitAsyncListeners();
        Position partial = position(infy);
        PositionGtt gtt = gtts.active(partial.id()).orElseThrow();
        assertThat(gtt.quantity()).isEqualTo(4);
        assertThat(gtt.stop()).isEqualByComparingTo("93.00");
        assertThat(gtt.goal()).isEqualByComparingTo("120.00");
        assertThat(atBroker(gtt.brokerGttId())).satisfies(g -> {
            assertThat(g.type()).isEqualTo(Gtt.Type.OCO);
            assertThat(g.status()).isEqualTo(Gtt.Status.ACTIVE);
            assertThat(g.quantity()).isEqualTo(4);
            assertThat(g.triggers()).usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("93.00"), new BigDecimal("120.00"));
            assertThat(g.legs()).extracting(Gtt.Leg::side).containsOnly(Side.SELL);
        });
        assertThat(audited(AuditEventType.GTT_PLACED, partial.id())).hasSize(1);

        fake.partialFill(brokerOrderId, 6, new BigDecimal("99.00"));
        fake.flush();
        awaitAsyncListeners();
        PositionGtt full = gtts.active(partial.id()).orElseThrow();
        assertThat(full.brokerGttId()).isEqualTo(gtt.brokerGttId());
        assertThat(full.quantity()).isEqualTo(10);
        assertThat(atBroker(full.brokerGttId()).quantity()).isEqualTo(10);
        assertThat(audited(AuditEventType.GTT_MODIFIED, partial.id())).isNotEmpty();

        assertThat(swing.book(ExecutionMode.PAPER)).singleElement().satisfies(row -> {
            assertThat(row.gtt()).isEqualTo("ACTIVE");
            assertThat(row.gttId()).isEqualTo(full.brokerGttId());
            assertThat(row.stop()).isEqualByComparingTo("93.00");
        });
        assertThat(gtts.unprotected(ExecutionMode.PAPER)).isEmpty();
        assertThat(swing.reconcile()).isEmpty();
    }

    @Test
    void stopsOnlyTightenAndAWideningIsAManualAuditedAction() throws Exception {
        Position p = enter(infy);
        SwingPosition s = swing.open(ExecutionMode.PAPER).get(0);

        swing.moveStop(s.id(), new BigDecimal("95.00"), false, "trail");
        PositionGtt moved = gtts.active(p.id()).orElseThrow();
        assertThat(moved.stop()).isEqualByComparingTo("95.00");
        assertThat(atBroker(moved.brokerGttId()).triggers().get(0)).isEqualByComparingTo("95.00");

        assertThatThrownBy(() -> swing.moveStop(s.id(), new BigDecimal("94.00"), false, "trail")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only tighten");
        assertThat(gtts.active(p.id()).orElseThrow().stop()).isEqualByComparingTo("95.00");

        swing.moveStop(s.id(), new BigDecimal("94.00"), true, "admin");
        assertThat(gtts.active(p.id()).orElseThrow().stop()).isEqualByComparingTo("94.00");
        assertThat(audited(AuditEventType.GTT_MODIFIED, p.id())).anySatisfy(r -> {
            assertThat(r.payload().get("widened")).isEqualTo(true);
            assertThat(r.payload().get("stopTo")).isEqualTo("94.00");
        });

        // the REST surface: tightening needs an Idempotency-Key, and a widening without "widen" is refused
        String token = adminAccessToken();
        HttpHeaders headers = bearer(token);
        ResponseEntity<Map> noKey = rest.exchange("/api/v1/swing/positions/" + s.id() + "/stop", HttpMethod.PUT,
                new HttpEntity<>(Map.of("stop", "96.00", "widen", false), headers), Map.class);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<Map> tightened = rest.exchange("/api/v1/swing/positions/" + s.id() + "/stop", HttpMethod.PUT,
                new HttpEntity<>(Map.of("stop", "96.00", "widen", false), headers), Map.class);
        assertThat(tightened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(String.valueOf(tightened.getBody().get("stop")))).isEqualByComparingTo("96.00");
        ResponseEntity<Map> lower = rest.exchange("/api/v1/swing/positions/" + s.id() + "/stop", HttpMethod.PUT,
                new HttpEntity<>(Map.of("stop", "90.00", "widen", false), headers), Map.class);
        assertThat(lower.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aGapDownThroughTheStopFillsAtTheOpenAndClosesTheSwingPosition() throws Exception {
        Position p = enter(infy);
        String gttId = gtts.active(p.id()).orElseThrow().brokerGttId();

        clock.setIst(DAY2 + "T09:15:00");
        fake.injectQuote(infy, "90.00"); // the session opens below the 93.00 stop
        fake.flush();
        awaitAsyncListeners();
        fake.flush();
        awaitAsyncListeners();

        assertThat(atBroker(gttId).status()).isEqualTo(Gtt.Status.TRIGGERED);
        Position closed = position(infy);
        assertThat(closed.netQuantity()).isZero();
        assertThat(closed.realizedPnl().toRupeesString()).isEqualTo("-100.00"); // filled at the open (90), not at the stop (93)
        SwingPosition done = swing.closed(ExecutionMode.PAPER, 5).get(0);
        assertThat(done.exitPrice()).isEqualByComparingTo("90.00");
        assertThat(done.holdingDays()).isEqualTo(1);
        assertThat(gtts.live(p.id())).isEmpty();
        assertThat(audited(AuditEventType.GTT_TRIGGERED, p.id())).hasSize(1);
        assertThat(swing.reconcile()).isEmpty();
    }

    @Test
    void aGttThatDisappearsAtTheBrokerIsFlaggedAndBlocksNewSwingEntries() throws Exception {
        Position p = enter(infy);
        String gttId = gtts.active(p.id()).orElseThrow().brokerGttId();

        fake.disableGtt(gttId);
        List<ReconciliationIssue> issues = swing.reconcile();
        assertThat(issues).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo(GttService.GTT_MISSING);
            assertThat(i.instrumentId()).isEqualTo(infy);
        });
        assertThat(gtts.live(p.id()).orElseThrow().status()).isEqualTo(PositionGtt.Status.MISSING);
        assertThat(audited(AuditEventType.GTT_MISSING, p.id())).hasSize(1);
        assertThat(swing.book(ExecutionMode.PAPER).get(0).gtt()).isEqualTo("MISSING");

        price(tcs, "100.00");
        assertThatThrownBy(() -> engine.submit(deliveryBuy(tcs, 5, OrderType.MARKET, null, "93.00", "120.00")))
                .isInstanceOfSatisfying(ExecutionException.RiskRejected.class, e -> assertThat(e.checks())
                        .anySatisfy(c -> {
                            assertThat(c.name()).isEqualTo("swingProtection");
                            assertThat(c.passed()).isFalse();
                            assertThat(c.message()).contains("NSE:INFY");
                        }));

        // fixed by replacing the protection: the issue resolves and entries are allowed again
        gtts.protect(position(infy), new BigDecimal("93.00"), new BigDecimal("120.00"), "admin");
        assertThat(swing.reconcile()).noneMatch(i -> GttService.GTT_MISSING.equals(i.kind()));
        price(tcs, "100.00");
        engine.submit(deliveryBuy(tcs, 5, OrderType.MARKET, null, "93.00", "120.00"));
        fake.flush();
        awaitAsyncListeners();
        assertThat(gtts.active(position(tcs).id())).isPresent();
    }

    @Test
    void closingThePositionCancelsItsGttInTheSameOperation() throws Exception {
        Position p = enter(infy);
        String gttId = gtts.active(p.id()).orElseThrow().brokerGttId();

        price(infy, "104.00");
        engine.closePosition(infy, Product.CNC, null);
        fake.flush();
        awaitAsyncListeners();

        assertThat(atBroker(gttId).status().isLive()).isFalse();
        assertThat(gtts.live(p.id())).isEmpty();
        assertThat(audited(AuditEventType.GTT_CANCELLED, p.id())).hasSize(1);
        assertThat(position(infy).netQuantity()).isZero();
        assertThat(swing.open(ExecutionMode.PAPER)).isEmpty();
    }

    @Test
    void aPositionPastItsHoldingLimitIsClosedAtTheOpenAndTheReviewListsLosersAfterTenSessions() throws Exception {
        Position p = enter(infy);
        String gttId = gtts.active(p.id()).orElseThrow().brokerGttId();

        clock.setIst("2026-12-22T16:00:00"); // 10 sessions after the entry, below it
        price(infy, "97.00");
        assertThat(swing.review(ExecutionMode.PAPER)).singleElement().satisfies(r -> assertThat(r.daysHeld()).isEqualTo(10));
        assertThat(swing.timeExitsDue(ExecutionMode.PAPER)).isEmpty();

        clock.setIst("2027-01-21T09:15:30"); // more than 30 sessions later
        price(infy, "98.00");
        assertThat(swing.timeExitsDue(ExecutionMode.PAPER)).hasSize(1);
        assertThat(swing.timeExit(ExecutionMode.PAPER)).isEqualTo(1);
        fake.flush();
        awaitAsyncListeners();
        assertThat(position(infy).netQuantity()).isZero();
        assertThat(atBroker(gttId).status().isLive()).isFalse();
        assertThat(swing.open(ExecutionMode.PAPER)).isEmpty();
    }

    @Test
    void anOrphanGttAtTheBrokerIsFlaggedAndLeftInPlace() {
        fake.injectQuote(infy, "100.00");
        String orphan = fake.placeGtt(new Gtt.Request(infy, Gtt.Type.SINGLE, List.of(new BigDecimal("90.00")), new BigDecimal("100.00"),
                List.of(new Gtt.Leg(Side.SELL, 3, OrderType.MARKET, new BigDecimal("90.00"), Product.CNC))));

        assertThat(swing.reconcile()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo(GttService.GTT_ORPHAN);
            assertThat(i.observed()).isEqualTo(orphan);
        });
        assertThat(atBroker(orphan).status()).isEqualTo(Gtt.Status.ACTIVE);
    }
}
