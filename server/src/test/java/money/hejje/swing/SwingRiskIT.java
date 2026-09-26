package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.broker.Gtt;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.MutableClock;
import money.hejje.events.EventService;
import money.hejje.events.EventType;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ExecutionException;
import money.hejje.execution.GttService;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.risk.KillSwitchAction;
import money.hejje.risk.RiskCheck;
import money.hejje.risk.RiskService;
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
 * Plan M11.3 acceptance through the real order path: the swing limits reject by name (edited over the API), the kill
 * switch leaves swing positions with their GTTs (and a separate "close swing book" exits them), and the risk dashboard
 * shows the gap-adjusted overnight risk.
 */
class SwingRiskIT extends AbstractIntegrationTest {

    static final String DAY = "2026-12-15"; // a Tuesday; the next session is 2026-12-16
    static final String RBI_TITLE = "SwingRiskIT RBI decision";

    @Autowired ExecutionEngine engine;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired SwingService swing;
    @Autowired GttService gtts;
    @Autowired RiskService risk;
    @Autowired EventService events;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;

    UUID infy;
    UUID tcs;
    UUID sbin;

    /** The seeded PAPER swing limits (V54). */
    static void defaultSwingLimits(JdbcTemplate jdbc) {
        jdbc.update("""
                UPDATE swing_limits SET swing_capital_paise = 50000000, max_open_positions = 6, max_risk_per_position_paise = 250000, gap_allowance_pct = 3.00,
                    max_overnight_risk_paise = 1000000, max_positions_per_industry = 2, block_before_events = TRUE, block_surveillance = TRUE WHERE mode = 'PAPER'
                """);
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record, swing_position, gtt, trade_review CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE WHERE mode = 'PAPER'");
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        jdbc.update("UPDATE risk_limits SET mandatory_stop = FALSE, no_reentry_minutes = 0, max_trades_per_day = 100000 WHERE mode = 'PAPER'");
        defaultSwingLimits(jdbc);
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        sbin = instruments.resolve("NSE:SBIN").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear(); // other suites leave newer-stamped quotes behind; the cache ignores older ticks
        clock.setIst(DAY + "T10:00:00");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM market_event WHERE title = ?", RBI_TITLE);
        jdbc.update("DELETE FROM surveillance_snapshot WHERE session_date = ?", LocalDate.parse(DAY));
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE WHERE mode = 'PAPER'");
        defaultSwingLimits(jdbc);
        quoteCache.clear(); // this suite's December ticks must not shadow later suites' earlier ones
        clock.set(java.time.Instant.now());
    }

    /** The price everywhere: the fake broker's quote and a fresh tick in Hejje's quote cache at the clock's time. */
    void price(UUID instrument, String price) {
        fake.injectQuote(instrument, price);
        pipeline.onTick(new MarketTick(instrument, clock.instant(), new BigDecimal(price), null, null, 0, 0, MarketTick.Mode.LTP));
    }

    OrderIntentCommand delivery(UUID instrument, Side side, int qty, String stop, String goal) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, instrument, side,
                Quantity.of(qty), OrderType.MARKET, Product.CNC, null, null, stop == null ? null : Price.of(stop), goal == null ? null : Price.of(goal), null,
                OrderReason.MANUAL);
    }

    Position enter(UUID instrument, int qty) throws InterruptedException {
        price(instrument, "100.00");
        engine.submit(delivery(instrument, Side.BUY, qty, "93.00", "120.00"));
        fake.flush();
        awaitAsyncListeners();
        return orders.positions(ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(instrument) && p.product() == Product.CNC).findFirst().orElseThrow();
    }

    void rejectedBy(OrderIntentCommand command, String check) {
        assertThatThrownBy(() -> engine.submit(command)).isInstanceOfSatisfying(ExecutionException.RiskRejected.class,
                e -> assertThat(e.checks().stream().filter(c -> !c.passed()).map(RiskCheck::name)).contains(check));
    }

    @Test
    void theSwingLimitsRejectEntriesByNameAndAreEditable() throws Exception {
        // edited over the API like the risk limits (audited)
        HttpHeaders admin = bearer(adminAccessToken());
        ResponseEntity<Map> put = rest.exchange("/api/v1/swing/limits", HttpMethod.PUT, new HttpEntity<>(Map.of("swingCapitalPaise", 50_000_000,
                "maxOpenPositions", 2, "maxRiskPerPositionPaise", 250_000, "gapAllowancePct", "3.00", "maxOvernightRiskPaise", 1_000_000,
                "maxPositionsPerIndustry", 1, "blockBeforeEvents", true, "blockSurveillance", true), admin), Map.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(swing.limits(ExecutionMode.PAPER).maxOpenPositions()).isEqualTo(2);

        // risk per position: 300 × (7 + 3) = 3,000 > 2,500
        price(infy, "100.00");
        rejectedBy(delivery(infy, Side.BUY, 300, "93.00", "120.00"), "swingRiskPerPosition");
        // a stop is required; long only
        rejectedBy(delivery(infy, Side.BUY, 10, null, null), "swingStop");
        rejectedBy(delivery(infy, Side.SELL, 10, null, null), "swingLongOnly");

        enter(infy, 10);
        // industry: INFY and TCS are both Information Technology in the swing universe
        price(tcs, "100.00");
        rejectedBy(delivery(tcs, Side.BUY, 10, "93.00", "120.00"), "swingIndustry");

        enter(sbin, 10);
        // open positions: two already
        UUID itc = instruments.resolve("NSE:ITC").map(Instrument::id).orElseThrow();
        price(itc, "100.00");
        rejectedBy(delivery(itc, Side.BUY, 10, "93.00", "120.00"), "swingOpenPositions");

        // the session before an RBI decision
        jdbc.update("UPDATE swing_limits SET max_open_positions = 6 WHERE mode = 'PAPER'");
        events.add(EventType.RBI_POLICY, null, RBI_TITLE, LocalDate.parse("2026-12-16"), LocalTime.of(10, 0), null, 1.0, "test");
        price(itc, "100.00");
        rejectedBy(delivery(itc, Side.BUY, 10, "93.00", "120.00"), "swingEventNextSession");
        jdbc.update("DELETE FROM market_event WHERE title = ?", RBI_TITLE);

        // a stock under surveillance
        jdbc.update("INSERT INTO surveillance_snapshot (session_date, fetched_at) VALUES (?, now())", LocalDate.parse(DAY));
        jdbc.update("INSERT INTO surveillance_flag (session_date, symbol, flag, code) VALUES (?, 'NSE:ITC', 'ASM_ST_I', 'STASM - I')", LocalDate.parse(DAY));
        price(itc, "100.00");
        rejectedBy(delivery(itc, Side.BUY, 10, "93.00", "120.00"), "swingSurveillance");
        jdbc.update("DELETE FROM surveillance_snapshot WHERE session_date = ?", LocalDate.parse(DAY));

        // overnight risk: 2 × 100 held; 850 × 10 = 8,500 would make 8,700 of 10,000 fine, 990 × 10 = 9,900 would not ... per position caps first,
        // so lift it to isolate the overnight budget
        jdbc.update("UPDATE swing_limits SET max_risk_per_position_paise = 10000000 WHERE mode = 'PAPER'");
        price(itc, "100.00");
        rejectedBy(delivery(itc, Side.BUY, 990, "93.00", "120.00"), "swingOvernightRisk");
        // capital: 2 × 1,000 deployed; 5,000 shares at 100 = 5,00,000 more
        jdbc.update("UPDATE swing_limits SET max_overnight_risk_paise = 100000000 WHERE mode = 'PAPER'");
        price(itc, "100.00");
        rejectedBy(delivery(itc, Side.BUY, 5_000, "93.00", "120.00"), "swingCapital");

        // and within every limit the entry goes through, sized from the gap-adjusted budget
        jdbc.update("UPDATE swing_limits SET max_risk_per_position_paise = 250000, max_overnight_risk_paise = 1000000 WHERE mode = 'PAPER'");
        SwingRisk.Size size = swing.size(ExecutionMode.PAPER, new BigDecimal("100.00"), new BigDecimal("93.00"));
        assertThat(size.quantity()).isEqualTo(250);
        price(itc, "100.00");
        engine.submit(delivery(itc, Side.BUY, size.quantity(), "93.00", "120.00"));
        fake.flush();
        awaitAsyncListeners();
        assertThat(swing.open(ExecutionMode.PAPER)).hasSize(3);
    }

    @Test
    void theKillSwitchLeavesSwingPositionsWithTheirGttsAndTheSwingBookClosesSeparately() throws Exception {
        Position swingPosition = enter(infy, 10);
        String gttId = gtts.active(swingPosition.id()).orElseThrow().brokerGttId();
        // an intraday position alongside
        price(tcs, "100.00");
        engine.submit(new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, tcs, Side.BUY,
                Quantity.of(5), OrderType.MARKET, Product.MIS, null, null, Price.of("98.00"), Price.of("104.00"), null, OrderReason.MANUAL));
        fake.flush();
        awaitAsyncListeners();

        risk.activate(ExecutionMode.PAPER, KillSwitchAction.CLOSE_ALL_POSITIONS, RiskService.CLOSE_ALL_CONFIRMATION, "test");
        fake.flush();
        awaitAsyncListeners();

        assertThat(orders.openPositions(ExecutionMode.PAPER)).singleElement().satisfies(p -> {
            assertThat(p.product()).isEqualTo(Product.CNC);
            assertThat(p.instrumentId()).isEqualTo(infy);
        });
        assertThat(gtts.active(swingPosition.id())).isPresent();
        assertThat(fake.getGtts()).filteredOn(g -> g.id().equals(gttId)).singleElement().satisfies(g -> assertThat(g.status()).isEqualTo(Gtt.Status.ACTIVE));
        // the kill switch stops new swing entries too
        price(sbin, "100.00");
        assertThatThrownBy(() -> engine.submit(delivery(sbin, Side.BUY, 10, "93.00", "120.00"))).isInstanceOf(ExecutionException.class)
                .hasMessageContaining("Kill switch");

        // the separate action needs its typed confirmation, and exits the swing book with its GTTs
        HttpHeaders headers = bearer(adminAccessToken());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<Map> wrong = rest.exchange("/api/v1/swing/close-all", HttpMethod.POST, new HttpEntity<>(Map.of("confirmation", "CLOSE ALL"), headers), Map.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        price(infy, "101.00");
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<Map> closed = rest.exchange("/api/v1/swing/close-all", HttpMethod.POST,
                new HttpEntity<>(Map.of("confirmation", SwingService.CLOSE_BOOK_CONFIRMATION), headers), Map.class);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().get("closed")).isEqualTo(1);
        fake.flush();
        awaitAsyncListeners();
        assertThat(orders.openPositions(ExecutionMode.PAPER)).isEmpty();
        assertThat(fake.getGtts()).filteredOn(g -> g.id().equals(gttId)).singleElement().satisfies(g -> assertThat(g.status().isLive()).isFalse());
    }

    @Test
    void theRiskDashboardShowsTheGapAdjustedOvernightRisk() throws Exception {
        enter(infy, 10);
        enter(sbin, 20);
        price(infy, "104.00");
        price(sbin, "101.00");

        // INFY 10 × (104 − 93 + 3.12) = 141.20; SBIN 20 × (101 − 93 + 3.03) = 220.60
        HttpHeaders headers = bearer(adminAccessToken());
        ResponseEntity<Map> dashboard = rest.exchange("/api/v1/risk", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(money(dashboard.getBody().get("overnightRisk"))).isEqualByComparingTo("361.80");
        assertThat(money(dashboard.getBody().get("overnightRiskBudget"))).isEqualByComparingTo("10000.00");
        assertThat(dashboard.getBody().get("swingPositions")).isEqualTo(2);
        assertThat(dashboard.getBody().get("openPositions")).isEqualTo(0); // the intraday book

        ResponseEntity<Map> detail = rest.exchange("/api/v1/swing/risk", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(money(detail.getBody().get("overnightRisk"))).isEqualByComparingTo("361.80");
        assertThat((List<?>) detail.getBody().get("positions")).hasSize(2);
        assertThat(swing.overnightRisk(ExecutionMode.PAPER).overnightRisk().toRupees().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("361.80");
    }

    /** Money is JSON {@code {"paise": n}}; in rupees. */
    static BigDecimal money(Object json) {
        return new BigDecimal(String.valueOf(((Map<?, ?>) json).get("paise"))).movePointLeft(2);
    }
}
