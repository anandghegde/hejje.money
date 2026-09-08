package money.hejje.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ExecutionException;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class RiskIT extends AbstractIntegrationTest {

    @Autowired ExecutionEngine engine;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired RiskService risk;
    @Autowired AuditService audit;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    UUID infy;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        // reset limits to the seeded defaults (other suites relax them)
        jdbc.update("UPDATE risk_limits SET max_loss_per_day_paise = 500000, max_realized_loss_paise = 500000, max_total_loss_paise = 750000, "
                + "max_margin_utilization_pct = 80.00, max_open_positions = 5, max_trades_per_day = 20, max_risk_per_trade_paise = 200000, "
                + "max_quantity = 1000, max_notional_paise = 50000000, min_reward_risk = 1.00, mandatory_stop = TRUE, "
                + "max_stop_distance_pct = 5.00, no_new_trades_after = '14:45', no_averaging_down = TRUE, no_reentry_minutes = 10, "
                + "max_consecutive_losses = 3 WHERE mode = 'PAPER'");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        clock.setIst("2026-09-08T10:00:00");
    }

    OrderIntentCommand order(Side side, int qty, OrderReason reason) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, infy, side,
                Quantity.of(qty), OrderType.MARKET, Product.MIS, null, null,
                side == Side.BUY ? money.hejje.common.Price.of("1490.00") : null, null, null, reason);
    }

    @Test
    void killSwitchStopsNewOrdersButAllowsCloses() {
        fake.injectQuote(infy, "1500.00");
        // open a position first
        engine.submit(order(Side.BUY, 10, OrderReason.MANUAL));
        fake.flush();

        risk.activate(ExecutionMode.PAPER, KillSwitchAction.STOP_NEW_ORDERS, null, "test");
        assertThat(risk.killSwitch(ExecutionMode.PAPER).stopNewOrders()).isTrue();

        // a new BUY is rejected by risk
        Assertions.assertThatThrownBy(() -> engine.submit(order(Side.BUY, 10, OrderReason.MANUAL)))
                .isInstanceOfSatisfying(ExecutionException.RiskRejected.class,
                        e -> assertThat(e.checks()).anyMatch(c -> c.name().equals("killSwitch") && !c.passed()));

        // a POSITION_CLOSE is allowed through risk (reaches the broker)
        var close = engine.submit(order(Side.SELL, 10, OrderReason.POSITION_CLOSE));
        fake.flush();
        assertThat(orders.findById(close.id()).orElseThrow().state()).isIn(OrderState.FILLED, OrderState.BROKER_ACCEPTED, OrderState.OPEN);
    }

    @Test
    void dailyLossBreachAutoTripsKillSwitch() {
        // lose more than the 5,000 daily limit: buy 100 @1500 then sell 100 @1400 = -10,000 realized
        fake.injectQuote(infy, "1500.00");
        engine.submit(order(Side.BUY, 100, OrderReason.MANUAL));
        fake.flush();
        fake.injectQuote(infy, "1400.00");
        engine.submit(order(Side.SELL, 100, OrderReason.POSITION_CLOSE));
        fake.flush();

        // the next evaluation sees the breach and auto-trips the kill switch (reason DAILY_LOSS), rejecting the order
        Assertions.assertThatThrownBy(() -> engine.submit(order(Side.BUY, 10, OrderReason.MANUAL)))
                .isInstanceOf(ExecutionException.RiskRejected.class);
        assertThat(risk.killSwitch(ExecutionMode.PAPER).stopNewOrders()).isTrue();
        assertThat(risk.killSwitch(ExecutionMode.PAPER).reason()).isEqualTo("DAILY_LOSS");
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.KILL_SWITCH_ENABLED, null, 0, 50)).total()).isPositive();
    }

    @Test
    void endpointsLimitsDashboardSizingAndConfirmation() {
        String token = adminAccessToken();
        assertThat(rest.exchange("/api/v1/risk", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> limits = rest.exchange("/api/v1/risk/limits", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(limits.getBody()).containsKey("maxLossPerDay");

        ResponseEntity<Map> size = rest.exchange("/api/v1/risk/position-size", HttpMethod.POST,
                new HttpEntity<>(Map.of("entry", "100.00", "stop", "95.00", "riskPaise", 100000, "lotSize", 1, "maxQuantity", 0), bearer(token)), Map.class);
        assertThat(size.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(size.getBody().get("quantity")).isEqualTo(200);

        // CLOSE_ALL_POSITIONS without confirmation is 400
        ResponseEntity<Map> bad = rest.exchange("/api/v1/risk/kill-switch", HttpMethod.POST,
                new HttpEntity<>(Map.of("action", "CLOSE_ALL_POSITIONS"), bearer(token)), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> stop = rest.exchange("/api/v1/risk/kill-switch", HttpMethod.POST,
                new HttpEntity<>(Map.of("action", "STOP_NEW_ORDERS"), bearer(token)), Map.class);
        assertThat(stop.getBody()).containsEntry("stopNewOrders", true);

        ResponseEntity<Map> rearm = rest.exchange("/api/v1/risk/kill-switch", HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(rearm.getBody()).containsEntry("stopNewOrders", false);
    }
}
