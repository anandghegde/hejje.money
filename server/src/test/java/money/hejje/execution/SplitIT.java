package money.hejje.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
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

/** Split and smart (target-position) intents end to end in PAPER (plan M5.3). */
@SuppressWarnings({"unchecked", "rawtypes"})
class SplitIT extends AbstractIntegrationTest {

    @Autowired SplitService splits;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClientCredentialService clients;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;

    UUID infy;
    final UUID client = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PlanningITSupport.reset(jdbc);
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear();
        clock.setIst("2026-10-15T11:00:00");
        fake.injectQuote(infy, "1500.00");
    }

    @AfterEach
    void tearDown() {
        clock.set(java.time.Instant.now());
    }

    OrderIntentCommand buy(String key, int quantity) {
        return new OrderIntentCommand(client, key, ActorType.USER, "tester", null, null, infy, Side.BUY, Quantity.of(quantity), OrderType.MARKET, Product.MIS, null,
                null, Price.of("1495.00"), Price.of("1520.00"), null, OrderReason.MANUAL);
    }

    SplitOrder awaitStatus(UUID id, SplitOrder.Status status) throws InterruptedException {
        return PlanningITSupport.await(() -> splits.find(id).filter(s -> s.status() == status), "split " + status);
    }

    int net() {
        return orders.positions(ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(infy)).mapToInt(Position::netQuantity).sum();
    }

    HttpHeaders keyed(String key, String idempotencyKey) {
        HttpHeaders headers = bearer(key);
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    @Test
    void childrenRespectTheMaximumSizeAndTheDelay() throws Exception {
        String key = PlanningITSupport.executionKey(clients);
        Map<String, Object> body = Map.of("instrumentId", infy.toString(), "side", "BUY", "quantity", 250, "orderType", "MARKET", "product", "MIS",
                "stopPrice", "1495.00", "targetPrice", "1520.00", "split", Map.of("maxChildQuantity", 100, "delayMs", 300));
        long started = System.nanoTime();
        ResponseEntity<Map> created = rest.exchange("/api/v1/orders/intents", HttpMethod.POST, new HttpEntity<>(body, keyed(key, "split-rest-1")), Map.class);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) ((Map) created.getBody().get("split")).get("id"));
        SplitOrder done = PlanningITSupport.await(() -> splits.find(id).filter(x -> x.status() != SplitOrder.Status.WORKING), "the split to end");
        assertThat(done.status()).as("%s / children %s", done.detail(), splits.children(id)).isEqualTo(SplitOrder.Status.COMPLETED);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(done.filledQuantity()).isEqualTo(250);
        assertThat(done.children()).isEqualTo(3);
        List<HejjeOrder> children = splits.children(id);
        assertThat(children).extracting(HejjeOrder::quantity).containsExactlyInAnyOrder(100, 100, 50);
        assertThat(children).allMatch(c -> c.state() == OrderState.FILLED && id.equals(c.parentOrderId()));
        assertThat(elapsedMs).as("two 300 ms pauses between three children").isGreaterThanOrEqualTo(600);
        // children 2 and 3 fill within the account's 10-minute re-entry cooldown: waived (and recorded) for this split only
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_decision d JOIN hejje_order o ON o.intent_id = d.intent_id "
                + "WHERE o.parent_order_id = ? AND d.checks::text LIKE '%waived: child of split%'", Integer.class, id)).isEqualTo(2);
        PlanningITSupport.await(() -> net() == 250 ? Optional.of(true) : Optional.empty(), "position 250");
        Map view = rest.exchange("/api/v1/orders/splits/" + id, HttpMethod.GET, new HttpEntity<>(bearer(key)), Map.class).getBody();
        assertThat((List) view.get("children")).hasSize(3);
        assertThat((Map) view.get("split")).containsEntry("status", "COMPLETED");
    }

    @Test
    void theDeadlineStopsWhatIsLeft() throws Exception {
        SplitOrder s = splits.start(buy("split-deadline", 300), new SplitPolicy(100, 1500, null, false, 60));
        PlanningITSupport.await(() -> splits.find(s.id()).filter(x -> x.filledQuantity() == 100), "first child filled");
        clock.advance(Duration.ofMinutes(5));
        SplitOrder done = awaitStatus(s.id(), SplitOrder.Status.EXPIRED);
        assertThat(done.filledQuantity()).isEqualTo(100);
        assertThat(done.children()).isEqualTo(1);
        assertThat(done.detail()).contains("deadline passed with 100 of 300 filled");
    }

    @Test
    void anAdverseMoveCancelsTheRestWhenAsked() throws Exception {
        SplitOrder s = splits.start(buy("split-move", 300), new SplitPolicy(100, 800, new BigDecimal("1.0"), true, 600));
        assertThat(s.referencePrice()).isEqualByComparingTo("1500.00");
        PlanningITSupport.await(() -> splits.find(s.id()).filter(x -> x.filledQuantity() == 100), "first child filled");
        fake.injectQuote(infy, "1520.00");
        SplitOrder done = awaitStatus(s.id(), SplitOrder.Status.CANCELLED);
        assertThat(done.filledQuantity()).isEqualTo(100);
        assertThat(done.detail()).contains("price moved 1.33% against the order").contains("tolerance 1%");
    }

    @Test
    void theWholeIntentIsRiskCheckedBeforeAnyChild() {
        assertThatThrownBy(() -> splits.start(buy("split-too-big", 5000), new SplitPolicy(100, 0, null, false, 600)))
                .isInstanceOf(ExecutionException.RiskRejected.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM split_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hejje_order", Integer.class)).isZero();
    }

    @Test
    void aTargetPositionIsPlannedFromTheCurrentPosition() throws Exception {
        String key = PlanningITSupport.executionKey(clients);
        Map<String, Object> shortFifty = Map.of("instrumentId", infy.toString(), "side", "SELL", "quantity", 50, "orderType", "MARKET", "product", "MIS",
                "stopPrice", "1505.00", "targetPrice", "1480.00");
        ResponseEntity<Map> opened = rest.exchange("/api/v1/orders/intents", HttpMethod.POST, new HttpEntity<>(shortFifty, keyed(key, "smart-1")), Map.class);
        assertThat(opened.getStatusCode()).as("%s", opened.getBody()).isEqualTo(HttpStatus.CREATED);
        PlanningITSupport.await(() -> net() == -50 ? Optional.of(true) : Optional.empty(), "position -50");

        Map<String, Object> target = Map.of("instrumentId", infy.toString(), "targetPosition", 100, "orderType", "MARKET", "product", "MIS",
                "stopPrice", "1495.00", "targetPrice", "1520.00");
        ResponseEntity<Map> planned = rest.exchange("/api/v1/orders/intents", HttpMethod.POST, new HttpEntity<>(target, keyed(key, "smart-2")), Map.class);
        assertThat(planned.getStatusCode()).as(String.valueOf(planned.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat((Map) planned.getBody().get("plan")).containsEntry("current", -50).containsEntry("target", 100).containsEntry("delta", 150)
                .containsEntry("side", "BUY").containsEntry("quantity", 150);
        assertThat((Map) planned.getBody().get("order")).containsEntry("side", "BUY").containsEntry("quantity", 150);
        PlanningITSupport.await(() -> net() == 100 ? Optional.of(true) : Optional.empty(), "position +100");

        ResponseEntity<Map> noop = rest.exchange("/api/v1/orders/intents", HttpMethod.POST, new HttpEntity<>(target, keyed(key, "smart-3")), Map.class);
        assertThat(noop.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(noop.getBody()).containsEntry("noop", true);
        assertThat((Map) noop.getBody().get("plan")).containsEntry("delta", 0);

        Map<String, Object> both = Map.of("instrumentId", infy.toString(), "targetPosition", 0, "side", "SELL", "quantity", 100, "orderType", "MARKET",
                "product", "MIS");
        assertThat(rest.exchange("/api/v1/orders/intents", HttpMethod.POST, new HttpEntity<>(both, keyed(key, "smart-4")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
