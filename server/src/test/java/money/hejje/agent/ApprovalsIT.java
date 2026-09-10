package money.hejje.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.security.AgentPresets;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.strategy.StrategyService;
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

/** Agent-prepared orders with human confirmation (plan M4.4), end to end in PAPER on the fake broker. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ApprovalsIT extends AbstractIntegrationTest {

    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionEngine engine;
    @Autowired OrderService orders;
    @Autowired ClientCredentialService clients;
    @Autowired StrategyService strategies;

    UUID infy;
    String admin;
    String execution;
    List<UUID> deployments = new ArrayList<>();

    static final Map<String, Object> SPEC = Map.of("instrument", "NSE:INFY", "side", "BUY", "riskRupees", 1000, "entry", 1500, "stop", 1490, "target", 1520);

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE approval, trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        jdbc.update("UPDATE risk_limits SET max_loss_per_day_paise = 500000, max_realized_loss_paise = 500000, max_total_loss_paise = 750000, "
                + "max_margin_utilization_pct = 80.00, max_open_positions = 5, max_trades_per_day = 20, max_risk_per_trade_paise = 200000, "
                + "max_quantity = 1000, max_notional_paise = 50000000, min_reward_risk = 1.00, mandatory_stop = TRUE, "
                + "max_stop_distance_pct = 5.00, no_new_trades_after = '14:45', no_averaging_down = TRUE, no_reentry_minutes = 10, "
                + "max_consecutive_losses = 3 WHERE mode = 'PAPER'");
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        clock.setIst("2026-09-08T10:00:00");
        fake.injectQuote(infy, "1500.00");
        admin = adminAccessToken();
        execution = key("execution");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        for (UUID d : deployments) {
            strategies.updateDeployment(d, false, "ApprovalsIT done", "it");
        }
        deployments.clear();
        awaitAsyncListeners();
    }

    String key(String preset) {
        return clients.create("approvals-" + preset + "-" + UUID.randomUUID(), AgentPresets.scopes(preset), null, actor()).key();
    }

    static HejjePrincipal actor() {
        return new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
    }

    ResponseEntity<Map> tool(String token, String name, Map<String, Object> body, String idempotencyKey) {
        HttpHeaders headers = bearer(token);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange("/api/v1/agents/tools/" + name, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    static Map<String, Object> output(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) r.getBody().get("output");
    }

    ResponseEntity<Map> decide(String token, String id, String action, String idempotencyKey, Map<String, Object> body) {
        HttpHeaders headers = bearer(token);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange("/api/v1/approvals/" + id + "/" + action, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    Map<String, Object> approval(String id) {
        return rest.exchange("/api/v1/approvals/" + id, HttpMethod.GET, new HttpEntity<>(bearer(admin)), Map.class).getBody();
    }

    String submit(String token, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(SPEC);
        body.putAll(extra);
        Map<String, Object> view = output(tool(token, "submit_order_intent", body, null));
        assertThat(view).containsEntry("status", "PENDING").containsEntry("kind", "ORDER_NEW");
        assertThat((String) view.get("message")).contains("nothing has been placed yet");
        return (String) view.get("approvalId");
    }

    String intentStatus(Object intentId) {
        return jdbc.queryForObject("SELECT status FROM order_intent WHERE id = ?::uuid", String.class, intentId.toString());
    }

    OrderIntentCommand order(Side side, int qty, OrderType type, String limit, String stop, OrderReason reason) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, infy, side, Quantity.of(qty), type,
                Product.MIS, limit == null ? null : Price.of(limit), null, stop == null ? null : Price.of(stop), null, null, reason);
    }

    @Test
    void anExecutionKeyProposesAndAHumanApprovalFillsTheOrderInPaper() {
        Map<String, Object> proposal = output(tool(execution, "prepare_order", SPEC, null));
        assertThat(proposal).containsEntry("instrument", "NSE:INFY").containsEntry("quantity", 100).containsEntry("riskOutcome", "APPROVED")
                .containsEntry("policyDecision", "REQUIRE_APPROVAL").containsEntry("side", "BUY");
        assertThat(((Number) proposal.get("maxRisk")).doubleValue()).isEqualTo(1000.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval", Long.class)).isZero(); // prepare is a dry run

        String id = submit(execution, Map.of("rationale", "Opening-range breakout with room to the target"));
        Map<String, Object> pending = approval(id);
        String proposedIntent = (String) pending.get("intentId");
        assertThat(intentStatus(proposedIntent)).isEqualTo("PROPOSED");
        assertThat(pending).containsEntry("rationale", "Opening-range breakout with room to the target").containsEntry("requestedByType", "CLIENT");
        assertThat((List<Map<String, Object>>) rest.exchange("/api/v1/approvals?status=PENDING", HttpMethod.GET, new HttpEntity<>(bearer(admin)), List.class)
                .getBody()).extracting(a -> a.get("id")).contains(id);

        assertThat(decide(execution, id, "approve", "agent-self", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // no orders:execute
        assertThat(decide(admin, id, "approve", null, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        clock.advance(Duration.ofMinutes(1));
        fake.injectQuote(infy, "1500.00");
        String approveKey = "approve-" + id;
        ResponseEntity<Map> approved = decide(admin, id, "approve", approveKey, null);
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).containsEntry("status", "APPROVED").containsEntry("decidedBy", "admin");
        Map<String, Object> result = (Map<String, Object>) approved.getBody().get("result");
        String orderId = (String) result.get("orderId");
        fake.flush();
        HejjeOrder order = orders.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.state()).isEqualTo(OrderState.FILLED);
        assertThat(order.quantity()).isEqualTo(100);
        assertThat(intentStatus(proposedIntent)).isEqualTo("SUBMITTED");

        assertThat(((Map<String, Object>) decide(admin, id, "approve", approveKey, null).getBody().get("result"))).containsEntry("orderId", orderId);
        assertThat(decide(admin, id, "approve", "another-key", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // AGENT_RECOMMENDED (proposal, 10:00) → USER_APPROVED → RISK_CHECK_PASSED → ORDER_SUBMITTED (approval, 10:01)
        List<Map<String, Object>> audit = jdbc.queryForList("SELECT type, ts FROM audit_event WHERE order_intent_id IN (?::uuid, ?::uuid) ORDER BY ts",
                proposedIntent, result.get("executedIntentId"));
        Map<String, java.time.Instant> first = new LinkedHashMap<>();
        audit.forEach(row -> first.putIfAbsent((String) row.get("type"), ((Timestamp) row.get("ts")).toInstant()));
        assertThat(first).containsKeys("AGENT_RECOMMENDED", "USER_APPROVED", "RISK_CHECK_PASSED", "ORDER_SUBMITTED");
        assertThat(first.get("AGENT_RECOMMENDED")).isBefore(first.get("USER_APPROVED"));
        assertThat(first.get("USER_APPROVED")).isBeforeOrEqualTo(first.get("RISK_CHECK_PASSED"));
        assertThat(first.get("RISK_CHECK_PASSED")).isBeforeOrEqualTo(first.get("ORDER_SUBMITTED"));
    }

    @Test
    void cancelModifyAndCloseProposalsExecuteOnlyOnApproval() {
        HejjeOrder resting = engine.submit(order(Side.BUY, 10, OrderType.LIMIT, "1400.00", "1390.00", OrderReason.MANUAL));
        HejjeOrder other = engine.submit(order(Side.BUY, 10, OrderType.LIMIT, "1395.00", "1385.00", OrderReason.MANUAL));
        fake.flush();

        Map<String, Object> cancel = output(tool(execution, "cancel_order_intent", Map.of("orderId", resting.id().toString(), "rationale", "stale"), null));
        assertThat(cancel).containsEntry("kind", "ORDER_CANCEL").containsEntry("status", "PENDING");
        assertThat(orders.findById(resting.id()).orElseThrow().state()).isNotEqualTo(OrderState.CANCELLED);
        assertThat(decide(admin, (String) cancel.get("approvalId"), "approve", "cancel-1", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        fake.flush();
        assertThat(orders.findById(resting.id()).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);

        Map<String, Object> modify = output(tool(execution, "modify_order_intent", Map.of("orderId", other.id().toString(), "limitPrice", 1405), null));
        assertThat((String) modify.get("summary")).contains("limit 1405");
        assertThat(decide(admin, (String) modify.get("approvalId"), "approve", "modify-1", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        fake.flush();
        // the approval ran the modify through the execution engine (the broker holds the new limit; see PROGRESS M4.4 follow-ups)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'ORDER_MODIFIED' AND order_id = ?", Long.class, other.id())).isEqualTo(1L);
        engine.cancel(other.id());
        fake.flush();
        assertThat(tool(execution, "cancel_order_intent", Map.of("orderId", resting.id().toString()), null).getBody()).containsEntry("toolStatus", "CONFLICT");

        engine.submit(order(Side.BUY, 10, OrderType.MARKET, null, "1490.00", OrderReason.MANUAL));
        fake.flush();
        Map<String, Object> close = output(tool(execution, "close_position_intent", Map.of("instrument", "NSE:INFY", "rationale", "take it off"), null));
        assertThat((String) close.get("summary")).contains("Close NSE:INFY MIS position (net 10)");
        assertThat(decide(admin, (String) close.get("approvalId"), "approve", "close-1", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        fake.flush();
        assertThat(orders.openPositions(money.hejje.common.ExecutionMode.PAPER)).noneMatch(p -> p.instrumentId().equals(infy));
    }

    @Test
    void anExpiredApprovalCannotBeApproved() {
        String id = submit(execution, Map.of());
        String intent = (String) approval(id).get("intentId");
        clock.advance(Duration.ofMinutes(6));
        ResponseEntity<Map> late = decide(admin, id, "approve", "late-1", null);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) late.getBody().get("detail")).contains("EXPIRED");
        assertThat(approval(id)).containsEntry("status", "EXPIRED");
        assertThat(intentStatus(intent)).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'APPROVAL_EXPIRED' AND order_intent_id = ?::uuid", Long.class, intent))
                .isEqualTo(1L);
    }

    @Test
    void approvalRevalidatesAndFailsWhenTheDailyLossLimitWasHitInBetween() {
        String id = submit(execution, Map.of());
        String intent = (String) approval(id).get("intentId");
        // lose 10,000 against the 5,000 daily limit: buy 100 @1500, sell 100 @1400
        engine.submit(order(Side.BUY, 100, OrderType.MARKET, null, "1490.00", OrderReason.MANUAL));
        fake.flush();
        fake.injectQuote(infy, "1400.00");
        engine.submit(order(Side.SELL, 100, OrderType.MARKET, null, null, OrderReason.POSITION_CLOSE));
        fake.flush();

        ResponseEntity<Map> refused = decide(admin, id, "approve", "after-loss", null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) refused.getBody().get("detail")).contains("Policy denies").contains("Daily loss");
        Map<String, Object> failed = approval(id);
        assertThat(failed).containsEntry("status", "FAILED");
        assertThat((String) failed.get("decisionNote")).contains("daily loss threshold");
        assertThat(intentStatus(intent)).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hejje_order WHERE intent_id IN (SELECT id FROM order_intent WHERE reason = 'AGENT_PROPOSAL' "
                + "AND status <> 'PROPOSED' AND status <> 'DECLINED')", Long.class)).isZero();
    }

    String deploy(String slug, int autonomy) throws InterruptedException {
        String yaml = "name: " + slug + "\nuniverse: [NSE:INFY]\ntimeframe: 5m\ndirection: long\nentry:\n  all:\n    - close > opening_range_high\nstop:\n"
                + "  type: opening_range_low\ntarget:\n  type: risk_multiple\n  value: 2\ntrade_window:\n  start: \"09:30\"\n  end: \"15:00\"\nmax_trades_per_day: 1\n";
        Map<String, Object> created = rest.postForEntity("/api/v1/strategies", new HttpEntity<>(Map.of("yaml", yaml), bearer(admin)), Map.class).getBody();
        String strategyId = (String) created.get("strategyId");
        rest.postForEntity("/api/v1/strategies/" + strategyId + "/versions/1/status", new HttpEntity<>(Map.of("status", "PAPER", "force", true, "note", "it"),
                bearer(admin)), Map.class);
        ResponseEntity<Map> deployment = rest.postForEntity("/api/v1/strategies/" + strategyId + "/versions/1/deployments", new HttpEntity<>(Map.of("mode", "PAPER",
                "instruments", List.of("NSE:INFY"), "autonomyLevel", autonomy, "params", Map.of("risk_rupees", 2000)), bearer(admin)), Map.class);
        assertThat(deployment.getStatusCode().is2xxSuccessful()).as("%s", deployment.getBody()).isTrue();
        deployments.add(UUID.fromString((String) deployment.getBody().get("id")));
        awaitAsyncListeners();
        return slug;
    }

    @Test
    void autonomyLevelsZeroAndOneDenyPreparationForTheirStrategy() throws InterruptedException {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String low = deploy("appr_low_" + suffix, 1);
        String ok = deploy("appr_ok_" + suffix, 3);
        fake.injectQuote(infy, "1500.00");
        Map<String, Object> withLow = new HashMap<>(SPEC);
        withLow.put("strategy", low);
        ResponseEntity<Map> denied = tool(execution, "prepare_order", withLow, null);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).containsEntry("toolStatus", "DENIED");
        assertThat((String) denied.getBody().get("detail")).contains("autonomy level 1 < 2");
        assertThat(tool(execution, "submit_order_intent", withLow, null).getBody()).containsEntry("toolStatus", "DENIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval", Long.class)).isZero();

        Map<String, Object> withOk = new HashMap<>(SPEC);
        withOk.put("strategy", ok);
        Map<String, Object> proposal = output(tool(execution, "prepare_order", withOk, null));
        assertThat(proposal).containsEntry("policyDecision", "REQUIRE_APPROVAL").containsEntry("autonomyLevel", 3).containsEntry("strategy", ok);
    }

    @Test
    void researchKeysCannotPrepareAndAnAgentCannotApproveItsOwnProposal() {
        ResponseEntity<Map> research = tool(key("research"), "prepare_order", SPEC, null);
        assertThat(research.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(research.getBody()).containsEntry("toolStatus", "FORBIDDEN");
        String both = clients.create("approvals-both-" + UUID.randomUUID(), Set.of("market:read", "strategies:read", "orders:prepare", "orders:execute"), null,
                actor()).key();
        String id = submit(both, Map.of());
        ResponseEntity<Map> self = decide(both, id, "approve", "self-1", null);
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat((String) self.getBody().get("detail")).contains("cannot approve its own proposal");
        assertThat(approval(id)).containsEntry("status", "PENDING");
    }

    @Test
    void aRejectionIsFinalAndProposalsWithAnIdempotencyKeyAreCreatedOnce() {
        String id = submit(execution, Map.of());
        String intent = (String) approval(id).get("intentId");
        ResponseEntity<Map> rejected = decide(admin, id, "reject", "reject-1", Map.of("reason", "not today"));
        assertThat(rejected.getBody()).containsEntry("status", "REJECTED").containsEntry("decisionNote", "not today");
        assertThat(decide(admin, id, "reject", "reject-1", Map.of("reason", "not today")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decide(admin, id, "approve", "approve-after", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(intentStatus(intent)).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'USER_REJECTED' AND order_intent_id = ?::uuid", Long.class, intent))
                .isEqualTo(1L);

        Map<String, Object> first = output(tool(execution, "submit_order_intent", SPEC, "proposal-key-1"));
        Map<String, Object> again = output(tool(execution, "submit_order_intent", SPEC, "proposal-key-1"));
        assertThat(again.get("approvalId")).isEqualTo(first.get("approvalId"));
    }
}
