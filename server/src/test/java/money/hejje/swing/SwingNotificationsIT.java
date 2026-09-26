package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
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
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.GttService;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.notify.Notification;
import money.hejje.notify.NotificationService;
import money.hejje.notify.NotificationType;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan M11.6: every swing notification type has a test. Entry filled and GTT placed on an entry; the stop hit with a gap
 * through it; the goal hit; the time exit due at the next open; the GTT missing at the broker.
 */
class SwingNotificationsIT extends AbstractIntegrationTest {

    static final String DAY1 = "2027-02-01";

    @Autowired ExecutionEngine engine;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired SwingService swing;
    @Autowired GttService gtts;
    @Autowired NotificationService notifications;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;

    UUID infy;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record, swing_position, gtt, trade_review CASCADE");
        jdbc.execute("TRUNCATE notification_delivery, notification CASCADE");
        jdbc.update("UPDATE notification_rule SET enabled = TRUE, min_severity = 'INFO'");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE WHERE mode = 'PAPER'");
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        SwingRiskIT.defaultSwingLimits(jdbc);
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear();
        clock.setIst(DAY1 + "T10:00:00");
    }

    @AfterEach
    void tearDown() {
        quoteCache.clear();
        clock.set(java.time.Instant.now());
    }

    void price(String p) {
        fake.injectQuote(infy, p);
        pipeline.onTick(new MarketTick(infy, clock.instant(), new BigDecimal(p), null, null, 0, 0, MarketTick.Mode.LTP));
    }

    Position enter() throws InterruptedException {
        price("100.00");
        engine.submit(new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, infy, Side.BUY, Quantity.of(10),
                OrderType.MARKET, Product.CNC, null, null, Price.of("93.00"), Price.of("120.00"), null, OrderReason.MANUAL));
        fake.flush();
        awaitAsyncListeners();
        return orders.positions(ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(infy) && p.product() == Product.CNC).findFirst().orElseThrow();
    }

    void settle() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            fake.flush();
            awaitAsyncListeners();
        }
    }

    Notification await(NotificationType type) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<Notification> n = notifications.inbox(50).stream().filter(x -> x.type() == type).findFirst();
            if (n.isPresent()) {
                return n.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no " + type + " in " + notifications.inbox(50).stream().map(Notification::type).toList());
    }

    @Test
    void anEntryNotifiesTheFillAndItsGttAndAGapThroughTheStopNotifiesTheStopHit() throws Exception {
        enter();
        assertThat(await(NotificationType.SWING_ENTRY_FILLED).body()).contains("10 at 100.00", "stop 93.00", "goal 120.00");
        assertThat(await(NotificationType.GTT_PLACED).body()).contains("Stop 93.00", "goal 120.00");

        clock.setIst("2027-02-02T09:15:00");
        price("90.00");
        settle();
        Notification stop = await(NotificationType.SWING_STOP_HIT);
        assertThat(stop.title()).contains("gap-through");
        assertThat(stop.body()).contains("Exited at 90.00", "opened through the stop 93.00");
        assertThat(stop.type().severity()).isEqualTo(NotificationType.Severity.WARNING);
    }

    @Test
    void theGoalHitIsNotified() throws Exception {
        enter();
        clock.setIst("2027-02-03T11:00:00");
        price("121.00");
        settle();
        Notification goal = await(NotificationType.SWING_GOAL_HIT);
        assertThat(goal.title()).doesNotContain("gap-through");
        assertThat(goal.body()).contains("Exited at 120.00", "held 2 sessions");
    }

    @Test
    void theTimeExitAtTheNextOpenIsAnnouncedAfterTheClose() throws Exception {
        enter();
        clock.setIst("2027-03-15T15:45:00"); // 30 sessions after 1 February (no holidays in between here): the exit is at the next open
        price("101.00");
        List<SwingBookRow> due = swing.announceTimeExits(ExecutionMode.PAPER);
        assertThat(due).hasSize(1);
        assertThat(await(NotificationType.SWING_TIME_EXIT_DUE).body()).contains("Held 30 sessions (limit 30)");
    }

    @Test
    void aMissingGttIsACriticalNotification() throws Exception {
        Position p = enter();
        fake.disableGtt(gtts.active(p.id()).orElseThrow().brokerGttId());
        swing.reconcile();
        Notification missing = await(NotificationType.GTT_MISSING);
        assertThat(missing.type().severity()).isEqualTo(NotificationType.Severity.CRITICAL);
    }
}
