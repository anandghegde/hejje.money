package money.hejje.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
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

/** Baskets end to end in PAPER against the fake broker (plan M5.3). */
@SuppressWarnings({"unchecked", "rawtypes"})
class BasketIT extends AbstractIntegrationTest {

    @Autowired BasketService baskets;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired AuditService audit;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClientCredentialService clients;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;

    UUID infy;
    UUID tcs;
    UUID sbin;
    final UUID client = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PlanningITSupport.reset(jdbc);
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        sbin = instruments.resolve("NSE:SBIN").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear();
        clock.setIst("2026-10-15T10:00:00");
        fake.injectQuote(infy, "1500.00");
        fake.injectQuote(tcs, "3500.00");
        fake.injectQuote(sbin, "800.00");
    }

    @AfterEach
    void tearDown() {
        clock.set(java.time.Instant.now());
    }

    static BasketCommand.Leg buy(UUID instrument, int qty, String stop, String target) {
        return new BasketCommand.Leg(instrument, Side.BUY, qty, OrderType.MARKET, Product.MIS, null, null, stop == null ? null : Price.of(stop),
                target == null ? null : Price.of(target), false);
    }

    BasketCommand command(String key, Basket.Policy policy, Basket.Rollback rollback, BasketCommand.Leg... legs) {
        return new BasketCommand(client, key, ActorType.USER, "tester", "it", policy, rollback, null, null, OrderReason.MANUAL, List.of(legs));
    }

    Basket awaitStatus(UUID id, Basket.Status... statuses) throws InterruptedException {
        List<Basket.Status> wanted = List.of(statuses);
        return PlanningITSupport.await(() -> baskets.find(id).filter(b -> wanted.contains(b.status())), "basket " + wanted);
    }

    BasketLeg leg(Basket b, int sequence) {
        return b.legs().stream().filter(l -> l.sequence() == sequence).findFirst().orElseThrow();
    }

    int net(UUID instrument) {
        return orders.positions(ExecutionMode.PAPER).stream().filter(p -> p.instrumentId().equals(instrument)).mapToInt(Position::netQuantity).sum();
    }

    @Test
    void allOrNothingRollsBackTheFilledLegWhenTheBrokerFailsLegTwo() throws Exception {
        // leg 2 is a resting limit that the exchange then rejects
        BasketCommand.Leg resting = new BasketCommand.Leg(tcs, Side.BUY, 5, OrderType.LIMIT, Product.MIS, Price.of("3400.00"), null, Price.of("3390.00"),
                Price.of("3450.00"), false);
        Basket b = baskets.submit(command("aon-1", Basket.Policy.ALL_OR_NOTHING, Basket.Rollback.CLOSE_FILLED_LEGS, buy(infy, 10, "1495.00", "1520.00"), resting,
                buy(sbin, 10, "795.00", "820.00")));
        assertThat(b.marginRequired()).as(b.detail()).isNotNull();
        Basket waiting = PlanningITSupport.await(() -> baskets.find(b.id()).filter(x -> leg(x, 2).orderId() != null), "leg 2 placed");
        assertThat(leg(waiting, 1).status()).isEqualTo(BasketLeg.Status.FILLED);
        HejjeOrder second = PlanningITSupport.await(() -> orders.findById(leg(waiting, 2).orderId()).filter(o -> o.brokerOrderId() != null
                && o.state().isLive()), "leg 2 working at the broker: " + orders.findById(leg(waiting, 2).orderId()) + " / " + baskets.find(b.id()));
        fake.rejectFromBroker(second.brokerOrderId(), "exchange rejected: price band");

        Basket done = awaitStatus(b.id(), Basket.Status.ROLLED_BACK, Basket.Status.FAILED);
        assertThat(done.status()).as(done.detail()).isEqualTo(Basket.Status.ROLLED_BACK);
        assertThat(leg(done, 1).status()).isEqualTo(BasketLeg.Status.ROLLED_BACK);
        assertThat(leg(done, 1).rollbackOrderId()).isNotNull();
        assertThat(leg(done, 2).status()).isEqualTo(BasketLeg.Status.FAILED);
        assertThat(leg(done, 3).status()).isEqualTo(BasketLeg.Status.SKIPPED);
        assertThat(leg(done, 3).orderId()).isNull();
        HejjeOrder close = PlanningITSupport.await(() -> orders.findById(leg(done, 1).rollbackOrderId()).filter(o -> o.state() == OrderState.FILLED), "rollback fill");
        assertThat(close.side()).isEqualTo(Side.SELL);
        assertThat(close.quantity()).isEqualTo(10);
        PlanningITSupport.await(() -> net(infy) == 0 ? Optional.of(true) : Optional.empty(), "INFY flat");
        assertThat(net(sbin)).isZero();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.BASKET_FINISHED, null, 0, 100)).content())
                .anyMatch(a -> b.id().toString().equals(a.payload().get("basketId")) && "ROLLED_BACK".equals(a.payload().get("status")));
    }

    @Test
    void hedgeLegsArePlacedFirst() throws Exception {
        BasketCommand.Leg hedge = new BasketCommand.Leg(infy, Side.SELL, 10, OrderType.MARKET, Product.MIS, null, null, Price.of("1505.00"), Price.of("1480.00"), true);
        Basket b = baskets.submit(command("hedge-1", Basket.Policy.ALL_OR_NOTHING, Basket.Rollback.NONE, buy(sbin, 10, "795.00", "820.00"), hedge));
        Basket done = awaitStatus(b.id(), Basket.Status.COMPLETED, Basket.Status.FAILED, Basket.Status.ROLLED_BACK);
        assertThat(done.status()).as(done.detail()).isEqualTo(Basket.Status.COMPLETED);
        assertThat(leg(done, 2).executionOrder()).isZero();
        assertThat(leg(done, 1).executionOrder()).isEqualTo(1);
        assertThat(done.legs()).allMatch(l -> l.status() == BasketLeg.Status.FILLED);
    }

    @Test
    void bestEffortPlacesEveryLegAndReportsPartial() throws Exception {
        // leg 2 has no stop: the risk engine refuses it (a stop is mandatory), the others still go
        Basket b = baskets.submit(command("best-1", Basket.Policy.BEST_EFFORT, Basket.Rollback.CLOSE_FILLED_LEGS, buy(infy, 10, "1495.00", "1520.00"),
                buy(tcs, 5, null, null), buy(sbin, 10, "795.00", "820.00")));
        Basket done = awaitStatus(b.id(), Basket.Status.PARTIAL, Basket.Status.FAILED, Basket.Status.COMPLETED);
        assertThat(done.status()).as(done.detail()).isEqualTo(Basket.Status.PARTIAL);
        assertThat(leg(done, 1).status()).isEqualTo(BasketLeg.Status.FILLED);
        assertThat(leg(done, 2).status()).isEqualTo(BasketLeg.Status.FAILED);
        assertThat(leg(done, 2).detail()).startsWith("refused");
        assertThat(leg(done, 3).status()).isEqualTo(BasketLeg.Status.FILLED);
        assertThat(done.detail()).isEqualTo("2 of 3 legs filled");
    }

    @Test
    void theBasketMarginIsCheckedAsAWholeBeforeAnyLeg() {
        Basket b = baskets.submit(command("margin-1", Basket.Policy.ALL_OR_NOTHING, Basket.Rollback.NONE, buy(infy, 50_000, "1495.00", "1520.00"),
                buy(tcs, 50_000, "3495.00", "3520.00")));
        assertThat(b.status()).as(b.detail()).isEqualTo(Basket.Status.FAILED);
        assertThat(b.detail()).contains("basket margin").contains("exceeds available funds");
        assertThat(b.marginRequired().paise()).isGreaterThan(b.marginAvailable().paise());
        assertThat(b.legs()).allMatch(l -> l.status() == BasketLeg.Status.SKIPPED && l.orderId() == null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hejje_order", Integer.class)).isZero();
    }

    @Test
    void theRestApiNeedsAKeyAndIsIdempotent() throws Exception {
        String key = PlanningITSupport.executionKey(clients);
        Map<String, Object> body = Map.of("name", "rest basket", "policy", "BEST_EFFORT", "legs", List.of(
                Map.of("instrumentId", sbin.toString(), "side", "BUY", "quantity", 10, "stopPrice", "795.00", "targetPrice", "820.00")));
        ResponseEntity<Map> unkeyed = rest.exchange("/api/v1/baskets", HttpMethod.POST, new HttpEntity<>(body, bearer(key)), Map.class);
        assertThat(unkeyed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(unkeyed.getBody().get("detail"))).contains("Idempotency-Key");
        HttpHeaders headers = bearer(key);
        headers.set("Idempotency-Key", "rest-basket-1");
        ResponseEntity<Map> first = rest.exchange("/api/v1/baskets", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(first.getStatusCode()).as(String.valueOf(first.getBody())).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> again = rest.exchange("/api/v1/baskets", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        UUID id = UUID.fromString((String) first.getBody().get("id"));
        awaitStatus(id, Basket.Status.COMPLETED);
        Map view = rest.exchange("/api/v1/baskets/" + id, HttpMethod.GET, new HttpEntity<>(bearer(key)), Map.class).getBody();
        assertThat(view).containsEntry("status", "COMPLETED").containsEntry("source", "AGENT");
        assertThat((List<Map>) view.get("legs")).singleElement().satisfies(l -> assertThat(l).containsEntry("status", "FILLED"));
        assertThat((List<Map>) rest.exchange("/api/v1/baskets", HttpMethod.GET, new HttpEntity<>(bearer(key)), List.class).getBody())
                .anyMatch(x -> id.toString().equals(x.get("id")));
    }

    @Test
    void agentsProposeBasketsThatAHumanApproves() throws Exception {
        money.hejje.common.security.HejjePrincipal actor = new money.hejje.common.security.HejjePrincipal(UUID.randomUUID(), "admin",
                money.hejje.common.security.HejjePrincipal.Type.USER, money.hejje.common.security.ScopeCatalog.ALL);
        String agent = clients.create("basket-agent-" + UUID.randomUUID(), money.hejje.common.security.AgentPresets.scopes("execution"), null, actor).key();
        String human = PlanningITSupport.executionKey(clients);
        HttpHeaders asAgent = bearer(agent);
        asAgent.set("Idempotency-Key", "agent-basket-1");
        Map<String, Object> input = Map.of("name", "agent pair", "policy", "BEST_EFFORT", "rationale", "pair test", "legs", List.of(
                Map.of("instrument", "NSE:SBIN", "side", "BUY", "quantity", 10, "stopPrice", 795.0, "targetPrice", 820.0)));
        ResponseEntity<Map> proposed = rest.exchange("/api/v1/agents/tools/submit_basket_intent", HttpMethod.POST, new HttpEntity<>(input, asAgent), Map.class);
        assertThat(proposed.getStatusCode()).as("%s", proposed.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> view = (Map<String, Object>) proposed.getBody().get("output");
        assertThat(view).containsEntry("kind", "BASKET_NEW").containsEntry("status", "PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM basket", Integer.class)).as("nothing placed before a human approves").isZero();

        HttpHeaders asHuman = bearer(human);
        asHuman.set("Idempotency-Key", "agent-basket-approve");
        ResponseEntity<Map> approved = rest.exchange("/api/v1/approvals/" + view.get("approvalId") + "/approve", HttpMethod.POST, new HttpEntity<>(asHuman),
                Map.class);
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.OK);
        UUID basketId = UUID.fromString((String) ((Map) approved.getBody().get("result")).get("basketId"));
        Basket done = awaitStatus(basketId, Basket.Status.COMPLETED, Basket.Status.FAILED, Basket.Status.PARTIAL);
        assertThat(done.status()).as(done.detail()).isEqualTo(Basket.Status.COMPLETED);
        assertThat(done.reason()).isEqualTo(OrderReason.AGENT_PROPOSAL);
        assertThat(done.name()).isEqualTo("agent pair");
    }
}
