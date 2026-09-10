package money.hejje.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import money.hejje.AbstractIntegrationTest;
import money.hejje.agent.Approval;
import money.hejje.agent.ApprovalService;
import money.hejje.agent.ApprovalStatus;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ExecutionMode;
import money.hejje.common.event.MarketTick;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalEngine;
import money.hejje.signals.SignalService;
import money.hejje.signals.SignalStatus;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * External webhooks end to end (plan M5.5): a signed TradingView-style intent becomes a signal of the mapped PAPER
 * deployment (in Today) with an approval, or executes through AUTO at autonomy 4; signatures, the timestamp window and
 * replays; validation and mapping refusals; passphrase mode; MANUAL_EXTERNAL proposals.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class WebhookIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 18); // a Wednesday no other suite trades on
    static final String YAML = """
            name: it_webhook_orb
            universe: [NSE:INFY]
            timeframe: 5m
            direction: long
            entry:
              all:
                - close > opening_range_high
            stop:
              type: opening_range_low
            target:
              type: risk_multiple
              value: 2
            trade_window:
              start: "09:30"
              end: "12:00"
            max_trades_per_day: 3
            """;
    static final String INTENT = "{\"instrument\":\"NSE:INFY\",\"direction\":\"BUY\",\"entry\":\"1507.50\",\"stop\":\"1495.00\",\"target\":\"1531.00\","
            + "\"note\":\"breakout\"}";

    @Autowired StrategyService strategies;
    @Autowired SignalService signals;
    @Autowired SignalEngine engine;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired AuditService audit;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;
    @Autowired ApprovalService approvals;
    @Autowired ClientCredentialService clients;

    UUID infy;
    String admin;
    StrategyVersion version;
    StrategyDeployment deployment;

    @BeforeEach
    void setUp() {
        engine.stop();
        jdbc.execute("TRUNCATE webhook_delivery, webhook, approval, market_event, strategy_position, signal, strategy_score, backtest_trade, backtest, "
                + "strategy_deployment, strategy_version, strategy, trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        jdbc.update("UPDATE risk_limits SET max_loss_per_day_paise = 500000, max_realized_loss_paise = 500000, max_total_loss_paise = 750000, "
                + "max_margin_utilization_pct = 80.00, max_open_positions = 5, max_trades_per_day = 20, max_risk_per_trade_paise = 200000, "
                + "max_quantity = 1000, max_notional_paise = 50000000, min_reward_risk = 1.00, mandatory_stop = TRUE, max_stop_distance_pct = 5.00, "
                + "no_new_trades_after = '14:45', no_averaging_down = TRUE, no_reentry_minutes = 10, max_consecutive_losses = 3 WHERE mode = 'PAPER'");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear();
        pipeline.flush();
        fake.injectQuote(infy, "1507.50");
        clock.setIst(DAY + "T09:40:00");
        tick();
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        admin = clients.create("webhook-admin-" + UUID.randomUUID(), Set.of("admin", "market:read", "strategies:read"), null, actor).key();
        version = strategies.create(YAML, null, "admin");
        jdbc.update("UPDATE strategy_version SET status = 'PAPER' WHERE id = ?", version.id());
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        clock.set(Instant.now());
    }

    /** A fresh tick at the clock's time: readiness (market data) and the risk dry-run need live data. */
    void tick() {
        pipeline.onTick(new MarketTick(infy, clock.instant(), new BigDecimal("1507.50"), null, null, 0, 0, MarketTick.Mode.LTP));
    }

    void deploy(int level) throws InterruptedException {
        deployment = strategies.deploy(version.strategyId(), 1, ExecutionMode.PAPER, List.of("NSE:INFY"), level, new HashMap<>(Map.of("risk_rupees", 2000)),
                "admin");
        jdbc.update("INSERT INTO strategy_score (id, version_id, instrument_id, computed_at, base, components, adjustments, final) "
                + "VALUES (?, ?, ?, ?, 80, '[]'::jsonb, '[]'::jsonb, 85)", UUID.randomUUID(), version.id(), infy, clock.instant().atOffset(ZoneOffset.UTC));
        engine.start();
        engine.refresh();
        awaitAsyncListeners();
    }

    Map<String, Object> create(Map<String, Object> body) {
        ResponseEntity<Map> r = rest.exchange("/api/v1/webhooks", HttpMethod.POST, new HttpEntity<>(body, bearer(admin)), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    static String id(Map<String, Object> created) {
        return (String) ((Map<String, Object>) created.get("webhook")).get("id");
    }

    ResponseEntity<Map> post(String id, String body, HttpHeaders extra) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (extra != null) {
            h.addAll(extra);
        }
        return rest.exchange("/api/v1/webhooks/" + id, HttpMethod.POST, new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), h), Map.class);
    }

    HttpHeaders signed(String secret, String body, long epochSeconds) {
        String ts = Long.toString(epochSeconds);
        HttpHeaders h = new HttpHeaders();
        h.set("X-Hejje-Timestamp", ts);
        h.set("X-Hejje-Signature", WebhookSignatures.sign(secret, ts, body.getBytes(StandardCharsets.UTF_8)));
        return h;
    }

    ResponseEntity<Map> sendSigned(String id, String secret, String body) {
        return post(id, body, signed(secret, body, clock.instant().getEpochSecond()));
    }

    static <T> T await(Supplier<Optional<T>> probe, String what) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<T> v = probe.get();
            if (v.isPresent()) {
                return v.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    int signalCount() {
        return jdbc.queryForObject("SELECT count(*) FROM signal", Integer.class);
    }

    @Test
    void aSignedIntentBecomesASignalInTodayWithAnApproval() throws Exception {
        deploy(2);
        Map<String, Object> created = create(Map.of("name", "tv-orb", "strategyVersionId", version.id().toString(), "allowedInstruments", List.of("NSE:INFY")));
        String id = id(created);
        String secret = (String) created.get("secret");
        assertThat(secret).startsWith("whsec_");
        assertThat(jdbc.queryForObject("SELECT secret_enc FROM webhook WHERE id = ?::uuid", String.class, id)).startsWith("v1:").doesNotContain(secret);
        assertThat(rest.exchange("/api/v1/webhooks", HttpMethod.GET, new HttpEntity<>(bearer(admin)), String.class).getBody()).contains("tv-orb")
                .doesNotContain(secret);

        HttpHeaders headers = signed(secret, INTENT, clock.instant().getEpochSecond());
        ResponseEntity<Map> accepted = post(id, INTENT, headers);
        assertThat(accepted.getStatusCode()).as("%s", accepted.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        UUID signalId = UUID.fromString((String) accepted.getBody().get("signalId"));
        assertThat(accepted.getBody()).as("%s", accepted.getBody()).containsKey("approvalId");
        UUID approvalId = UUID.fromString((String) accepted.getBody().get("approvalId"));

        Signal signal = signals.find(signalId).orElseThrow();
        assertThat(signal.status()).isEqualTo(SignalStatus.ACTIVE);
        assertThat(signal.deploymentId()).isEqualTo(deployment.id());
        assertThat(signal.stop()).isEqualByComparingTo("1495.00");
        assertThat(signal.evidence()).singleElement().satisfies(e -> assertThat(e).containsEntry("source", "webhook").containsEntry("webhook", "tv-orb"));

        Map<String, Object> today = rest.exchange("/api/v1/today", HttpMethod.GET, new HttpEntity<>(bearer(admin)), Map.class).getBody();
        assertThat((List<Map<String, Object>>) today.get("ranked")).extracting(r -> r.get("signalId")).contains(signalId.toString());

        Approval approval = approvals.list(ApprovalStatus.PENDING, 50).stream().filter(a -> a.id().equals(approvalId)).findFirst().orElseThrow();
        assertThat(approval.signalId()).isEqualTo(signalId);
        assertThat(approval.requestedByType()).isEqualTo("WEBHOOK");
        assertThat(approval.requestedBy()).isEqualTo("webhook:tv-orb");
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.WEBHOOK_RECEIVED, null, 0, 50)).content())
                .anySatisfy(a -> assertThat(a.signalId()).isEqualTo(signalId));

        // the same delivery again is a replay; a tampered body or a stale timestamp does not authenticate
        assertThat(post(id, INTENT, headers).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(id, INTENT.replace("1495.00", "1400.00"), headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> stale = post(id, INTENT, signed(secret, INTENT, clock.instant().getEpochSecond() - 600));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat((String) stale.getBody().get("detail")).contains("outside");
        assertThat(signalCount()).isEqualTo(1);

        List<Map<String, Object>> deliveries = rest.exchange("/api/v1/webhooks/" + id + "/deliveries", HttpMethod.GET, new HttpEntity<>(bearer(admin)),
                List.class).getBody();
        assertThat(deliveries).extracting(d -> d.get("status")).containsExactlyInAnyOrder("ACCEPTED", "REPLAYED", "REJECTED", "REJECTED");
    }

    @Test
    void intentsOutsideTheMappingAreRefusedWithTheReason() throws Exception {
        deploy(2);
        Map<String, Object> created = create(Map.of("name", "tv-refusals", "strategyVersionId", version.id().toString(), "allowedInstruments",
                List.of("NSE:INFY")));
        String id = id(created);
        String secret = (String) created.get("secret");

        ResponseEntity<Map> sell = sendSigned(id, secret, INTENT.replace("\"BUY\"", "\"SELL\"").replace("1495.00", "1520.00").replace("1531.00", "1480.00"));
        assertThat(sell.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) sell.getBody().get("detail")).contains("LONG only");
        ResponseEntity<Map> other = sendSigned(id, secret, INTENT.replace("NSE:INFY", "NSE:TCS"));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) other.getBody().get("detail")).containsAnyOf("not allowed", "Unknown instrument");
        ResponseEntity<Map> badStop = sendSigned(id, secret, INTENT.replace("1495.00", "1510.00"));
        assertThat((String) badStop.getBody().get("detail")).contains("losing side");
        assertThat(sendSigned(id, secret, INTENT.replace("\"note\":\"breakout\"", "\"note\":\"late\"")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        clock.setIst(DAY + "T12:30:00");
        ResponseEntity<Map> late = sendSigned(id, secret, INTENT.replace("breakout", "after the window"));
        assertThat((String) late.getBody().get("detail")).contains("trade window");
        clock.setIst(DAY + "T09:45:00");
        tick();

        assertThat(rest.exchange("/api/v1/webhooks/" + id, HttpMethod.PUT, new HttpEntity<>(Map.of("enabled", false), bearer(admin)), Map.class).getBody())
                .containsEntry("enabled", false);
        assertThat(sendSigned(id, secret, INTENT.replace("breakout", "disabled")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(sendSigned(UUID.randomUUID().toString(), secret, INTENT).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        rest.exchange("/api/v1/webhooks/" + id, HttpMethod.PUT, new HttpEntity<>(Map.of("enabled", true), bearer(admin)), Map.class);

        // rotation: the old secret stops working at once
        Map<String, Object> rotated = rest.exchange("/api/v1/webhooks/" + id + "/rotate", HttpMethod.POST, new HttpEntity<>(bearer(admin)), Map.class).getBody();
        String fresh = (String) rotated.get("secret");
        assertThat(fresh).isNotEqualTo(secret);
        assertThat(sendSigned(id, secret, INTENT.replace("breakout", "old secret")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sendSigned(id, fresh, INTENT.replace("breakout", "new secret")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(signalCount()).isEqualTo(2);
    }

    @Test
    void passphraseModeServesTradingView() throws Exception {
        deploy(2);
        Map<String, Object> created = create(Map.of("name", "tv-pass", "strategyVersionId", version.id().toString(), "authMode", "PASSPHRASE"));
        String id = id(created);
        String secret = (String) created.get("secret");
        String body = "{\"passphrase\":\"" + secret + "\",\"timestamp\":\"" + clock.instant() + "\",\"instrument\":\"NSE:INFY\",\"direction\":\"buy\","
                + "\"stop\":\"1495\",\"target\":\"1531\",\"note\":\"tv\"}";
        ResponseEntity<Map> accepted = post(id, body, null);
        assertThat(accepted.getStatusCode()).as("%s", accepted.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(signals.find(UUID.fromString((String) accepted.getBody().get("signalId"))).orElseThrow().referencePrice()).isEqualByComparingTo("1507.50");
        assertThat(post(id, body, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(id, body.replace(secret, "whsec_wrong"), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForList("SELECT coalesce(payload::text, '') FROM webhook_delivery", String.class)).noneMatch(p -> p.contains(secret));
    }

    @Test
    void manualExternalWebhooksProposeAManualOrder() {
        Map<String, Object> created = create(Map.of("name", "scanner", "allowedInstruments", List.of("NSE:INFY")));
        String id = id(created);
        ResponseEntity<Map> accepted = sendSigned(id, (String) created.get("secret"),
                "{\"instrument\":\"NSE:INFY\",\"direction\":\"BUY\",\"entry\":\"1507.50\",\"stop\":\"1495.00\",\"target\":\"1531.00\",\"riskRupees\":1000}");
        assertThat(accepted.getStatusCode()).as("%s", accepted.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).doesNotContainKey("signalId");
        UUID approvalId = UUID.fromString((String) accepted.getBody().get("approvalId"));
        Approval approval = approvals.list(ApprovalStatus.PENDING, 50).stream().filter(a -> a.id().equals(approvalId)).findFirst().orElseThrow();
        assertThat(approval.requestedByType()).isEqualTo("WEBHOOK");
        assertThat(approval.signalId()).isNull();
        assertThat(jdbc.queryForObject("SELECT reason || ':' || status FROM order_intent WHERE id = ?", String.class, approval.intentId()))
                .isEqualTo("WEBHOOK:PROPOSED");
        assertThat(signalCount()).isZero();
    }

    @Test
    void atAutonomyFourTheExternalSignalExecutesThroughAuto() throws Exception {
        deploy(4);
        Map<String, Object> created = create(Map.of("name", "tv-auto", "strategyVersionId", version.id().toString()));
        ResponseEntity<Map> accepted = sendSigned(id(created), (String) created.get("secret"), INTENT);
        assertThat(accepted.getStatusCode()).as("%s", accepted.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).doesNotContainKey("approvalId");
        UUID signalId = UUID.fromString((String) accepted.getBody().get("signalId"));
        Signal executed = await(() -> signals.find(signalId).filter(s -> s.status() == SignalStatus.EXECUTED), "AUTO execution");
        assertThat(executed.orderId()).isNotNull();
        await(() -> audit.query(new AuditQuery(null, null, AuditEventType.AUTO_EXECUTED, null, 0, 50)).content().stream()
                .filter(a -> signalId.equals(a.signalId())).findFirst(), "AUTO_EXECUTED");
        assertThat(approvals.list(ApprovalStatus.PENDING, 50)).noneMatch(a -> signalId.equals(a.signalId()));
    }
}
