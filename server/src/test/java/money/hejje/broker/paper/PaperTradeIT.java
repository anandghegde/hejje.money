package money.hejje.broker.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.orders.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** End-to-end paper round trip: realized P&L net of fees, the cost breakdown endpoint, and the mode header. */
class PaperTradeIT extends AbstractIntegrationTest {

    @Autowired ExecutionEngine engine;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    UUID infy;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE WHERE mode = 'PAPER'");
        jdbc.update("UPDATE risk_limits SET mandatory_stop = FALSE, no_reentry_minutes = 0, max_trades_per_day = 100000 WHERE mode = 'PAPER'");
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        clock.setIst("2026-09-08T10:00:00");
    }

    OrderIntentCommand market(Side side, int qty, OrderReason reason) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, infy, side,
                Quantity.of(qty), OrderType.MARKET, Product.MIS, null, null, null, null, null, reason);
    }

    @Test
    void roundTripRealizedPnlIsNetOfFees() {
        fake.injectQuote(infy, "100.00");
        engine.submit(market(Side.BUY, 10, OrderReason.MANUAL));
        fake.flush();
        fake.injectQuote(infy, "110.00");
        engine.submit(market(Side.SELL, 10, OrderReason.POSITION_CLOSE));
        fake.flush();

        Position position = orders.positions(ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(infy)).findFirst().orElseThrow();
        assertThat(position.realizedPnl()).isEqualTo(Money.of("100.00"));       // gross (110-100)*10
        assertThat(position.fees()).isEqualTo(Money.of("1.13"));               // 0.42 buy + 0.71 sell
        assertThat(position.netRealizedPnl()).isEqualTo(Money.of("98.87"));
    }

    @Test
    void tradeCostsEndpointAndModeHeader() {
        fake.injectQuote(infy, "100.00");
        engine.submit(market(Side.BUY, 10, OrderReason.MANUAL));
        fake.flush();

        String token = adminAccessToken();
        ResponseEntity<List> trades = rest.exchange("/api/v1/trades", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(trades.getHeaders().getFirst("X-Hejje-Mode")).isEqualTo("PAPER");
        assertThat(trades.getBody()).isNotEmpty();
        String tradeId = (String) ((Map<String, Object>) trades.getBody().get(0)).get("id");

        ResponseEntity<Map> costs = rest.exchange("/api/v1/trades/" + tradeId + "/costs", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(costs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(costs.getBody()).containsKeys("brokerage", "stt", "exchangeTxn", "gst", "sebi", "stampDuty", "total");
    }
}
