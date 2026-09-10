package money.hejje.auto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import money.hejje.AbstractIntegrationTest;
import money.hejje.agent.Approval;
import money.hejje.agent.ApprovalService;
import money.hejje.agent.ApprovalStatus;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditRecord;
import money.hejje.audit.AuditService;
import money.hejje.backtest.SyntheticSessions;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.events.EventService;
import money.hejje.events.EventType;
import money.hejje.execution.ExecutionEngine;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.llm.FixtureLlmProvider;
import money.hejje.llm.LlmService;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import money.hejje.market.internal.InProcessTickBus;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.risk.KillSwitchAction;
import money.hejje.risk.RiskService;
import money.hejje.signals.PositionStatus;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalEngine;
import money.hejje.signals.SignalGeneratedEvent;
import money.hejje.signals.SignalService;
import money.hejje.signals.SignalStatus;
import money.hejje.signals.StrategyPosition;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AUTO execution end to end on a PAPER deployment at autonomy 4-5 (plan M5.2): the same pipeline as a human
 * confirmation, policy-held signals as approvals, the kill switch, re-entries and budgets, a restart mid-trade, and
 * no dependency on the LLM.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class AutoExecutionIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 14); // a Wednesday no other suite trades on
    static final String YAML = """
            name: it_auto_orb
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

    @Autowired StrategyService strategies;
    @Autowired SignalService signals;
    @Autowired SignalEngine engine;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired TickBus bus;
    @Autowired OrderService orders;
    @Autowired ExecutionEngine execution;
    @Autowired AuditService audit;
    @Autowired MutableClock clock;
    @Autowired HejjeClock hejjeClock;
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;
    @Autowired AutoExecutor auto;
    @Autowired RiskService risk;
    @Autowired EventService events;
    @Autowired ApprovalService approvals;
    @Autowired LlmService llm;
    @Autowired ApplicationEventPublisher publisher;

    UUID infy;
    String token;
    StrategyVersion version;
    StrategyDeployment deployment;

    @BeforeEach
    void setUp() {
        engine.stop();
        jdbc.execute("TRUNCATE approval, market_event, strategy_position, signal, strategy_score, backtest_trade, backtest, strategy_deployment, strategy_version, "
                + "strategy, trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
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
        jdbc.execute("TRUNCATE market_candle");
        clock.setIst(DAY + "T09:36:00");
        token = adminAccessToken();
        version = strategies.create(YAML, null, "admin");
        jdbc.update("UPDATE strategy_version SET status = 'PAPER' WHERE id = ?", version.id());
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        clock.set(Instant.now());
    }

    void deploy(int level, Map<String, Object> extra) throws InterruptedException {
        Map<String, Object> params = new HashMap<>(Map.of("risk_rupees", 2000));
        params.putAll(extra);
        deployment = strategies.deploy(version.strategyId(), 1, ExecutionMode.PAPER, List.of("NSE:INFY"), level, params, "admin");
        jdbc.update("INSERT INTO strategy_score (id, version_id, instrument_id, computed_at, base, components, adjustments, final) "
                + "VALUES (?, ?, ?, ?, 80, '[]'::jsonb, '[]'::jsonb, 85)", UUID.randomUUID(), version.id(), infy, ts(clock.instant()));
        engine.start();
        engine.refresh();
        awaitAsyncListeners();
        assertThat(engine.runner(deployment.id(), infy)).isPresent();
    }

    private void bar(String time, String open, String high, String low, String close) {
        Candle candle = SyntheticSessions.session(infy, DAY, List.of(SyntheticSessions.bar(open, high, low, close)), 50_000).get(0);
        Candle shifted = new Candle(infy, candle.timeframe(), DAY.atTime(LocalTime.parse(time)).atZone(SyntheticSessions.IST).toInstant(), candle.open(),
                candle.high(), candle.low(), candle.close(), candle.volume(), 0, false);
        bus.publish(new CandleClosedEvent(shifted));
        ((InProcessTickBus) bus).drain();
    }

    /** Opening range 1495..1505, then a breakout close at 1507 on the 09:30 bar (signal at 09:35). */
    private void openingRangeAndBreakout() {
        fake.injectQuote(infy, "1507.50");
        bar("09:15", "1500", "1505", "1495", "1500");
        bar("09:20", "1500", "1503", "1497", "1501");
        bar("09:25", "1501", "1504", "1498", "1502");
        bar("09:30", "1502", "1508", "1501", "1507");
    }

    /** A closed entry of the deployment earlier today (09:16-09:20), as if the runner had traded before. */
    private void earlierEntryToday() {
        UUID signalId = UUID.randomUUID();
        OffsetDateTime at = ts(DAY.atTime(9, 16).atZone(SyntheticSessions.IST).toInstant());
        OffsetDateTime closed = ts(DAY.atTime(9, 20).atZone(SyntheticSessions.IST).toInstant());
        jdbc.update("""
                INSERT INTO signal (id, version_id, strategy_id, deployment_id, instrument_id, mode, side, reference_price, stop, target, risk_per_unit,
                    bar_time, valid_until, evidence, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PAPER', 'BUY', 1500, 1495, 1510, 5, ?, ?, '[]'::jsonb, 'EXECUTED', ?, ?)
                """, signalId, version.id(), version.strategyId(), deployment.id(), infy, at, at, at, at);
        jdbc.update("""
                INSERT INTO strategy_position (id, signal_id, deployment_id, version_id, strategy_id, instrument_id, mode, side, quantity, entry_price,
                    initial_stop, stop, target, status, close_reason, exit_price, opened_at, closed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PAPER', 'BUY', 10, 1500, 1495, 1495, 1510, 'CLOSED', 'STOP', 1495, ?, ?, ?)
                """, UUID.randomUUID(), signalId, deployment.id(), version.id(), version.strategyId(), infy, at, closed, closed);
    }

    /** {@code n} closed paper trades of the version (no deployment). */
    private void paperTrades(int n) {
        for (int i = 0; i < n; i++) {
            UUID signalId = UUID.randomUUID();
            OffsetDateTime at = ts(clock.instant().minusSeconds(86_400L * (i + 2)));
            jdbc.update("""
                    INSERT INTO signal (id, version_id, strategy_id, instrument_id, mode, side, reference_price, stop, target, risk_per_unit, bar_time,
                        valid_until, evidence, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PAPER', 'BUY', 100, 99, 102, 1, ?, ?, '[]'::jsonb, 'EXECUTED', ?, ?)
                    """, signalId, version.id(), version.strategyId(), infy, at, at, at, at);
            jdbc.update("""
                    INSERT INTO strategy_position (id, signal_id, version_id, strategy_id, instrument_id, mode, side, quantity, entry_price, initial_stop, stop,
                        target, status, close_reason, exit_price, opened_at, closed_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'PAPER', 'BUY', 10, 100, 99, 99, 102, 'CLOSED', 'TARGET', 102, ?, ?, ?)
                    """, UUID.randomUUID(), signalId, version.id(), version.strategyId(), infy, at, at, at);
        }
    }

    static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
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

    Signal awaitSignal() throws InterruptedException {
        return await(() -> signals.list(null, null, 20).stream().filter(s -> deployment.id().equals(s.deploymentId())).findFirst(), "a signal");
    }

    Signal awaitStatus(UUID signalId, SignalStatus status) throws InterruptedException {
        return await(() -> signals.find(signalId).filter(s -> s.status() == status), "signal " + status);
    }

    StrategyPosition awaitPosition(Predicate<StrategyPosition> p) throws InterruptedException {
        return await(() -> signals.positions(false).stream().filter(x -> deployment.id().equals(x.deploymentId())).filter(p).findFirst(), "a position");
    }

    List<AuditRecord> auditFor(AuditEventType type, UUID signalId) {
        return audit.query(new AuditQuery(null, null, type, null, 0, 200)).content().stream().filter(r -> signalId.equals(r.signalId())).toList();
    }

    AuditRecord awaitAudit(AuditEventType type, UUID signalId) throws InterruptedException {
        return await(() -> auditFor(type, signalId).stream().findFirst(), type.name());
    }

    List<Approval> pendingFor(UUID signalId) {
        return approvals.list(ApprovalStatus.PENDING, 50).stream().filter(a -> signalId.equals(a.signalId())).toList();
    }

    int entryIntents(UUID signalId) {
        return jdbc.queryForObject("SELECT count(*) FROM order_intent WHERE signal_id = ? AND source = 'STRATEGY' AND status <> 'PROPOSED' "
                + "AND idempotency_key LIKE 'auto:%'", Integer.class, signalId);
    }

    @Test
    void anEligibleSignalExecutesAutomaticallyAsTheStrategy() throws Exception {
        deploy(4, Map.of());
        openingRangeAndBreakout();
        Signal signal = awaitSignal();
        Signal executed = awaitStatus(signal.id(), SignalStatus.EXECUTED);
        fake.flush();
        StrategyPosition open = awaitPosition(p -> p.status() == PositionStatus.OPEN && p.stopOrderId() != null);
        assertThat(open.signalId()).isEqualTo(signal.id());
        HejjeOrder entry = orders.findById(executed.orderId()).orElseThrow();
        assertThat(entry.state()).isEqualTo(OrderState.FILLED);
        OrderIntent intent = orders.findIntent(entry.intentId()).orElseThrow();
        assertThat(intent.source()).isEqualTo(ActorType.STRATEGY);
        assertThat(intent.actorId()).isEqualTo("it_auto_orb");
        assertThat(intent.clientId()).isEqualTo(SignalService.AUTO_CLIENT);
        awaitAudit(AuditEventType.AUTO_EXECUTED, signal.id());
        assertThat(auditFor(AuditEventType.AUTO_EXECUTED, signal.id())).singleElement().satisfies(a -> {
            assertThat(a.actorType()).isEqualTo(ActorType.STRATEGY);
            assertThat(a.orderId()).isEqualTo(entry.id());
            assertThat(a.payload()).containsEntry("decision", "ALLOW").containsEntry("rule", "auto_strategy").containsEntry("autonomyLevel", 4)
                    .containsEntry("score", 85).containsEntry("qualified", true);
        });
        assertThat(audit.query(new AuditQuery(null, null, null, entry.id(), 0, 50)).content()).extracting(AuditRecord::type)
                .contains(AuditEventType.ORDER_SUBMITTED, AuditEventType.AUTO_EXECUTED);
        assertThat(auditFor(AuditEventType.USER_APPROVED, signal.id())).isEmpty();
        assertThat(pendingFor(signal.id())).isEmpty();
    }

    @Test
    void eventRiskHighHoldsTheSignalForAHuman() throws Exception {
        events.add(EventType.RESULTS, "NSE:INFY", "Q2 results", DAY, LocalTime.of(16, 0), null, 1.0, "admin");
        deploy(4, Map.of());
        openingRangeAndBreakout();
        Signal signal = awaitSignal();
        Approval approval = await(() -> pendingFor(signal.id()).stream().findFirst(), "an approval");
        assertThat(approval.requestedByType()).isEqualTo("STRATEGY");
        assertThat(approval.requestedBy()).isEqualTo("it_auto_orb");
        assertThat(approval.requestedBySession()).isNull();
        assertThat(approval.policy().path("rule").asText()).isEqualTo("event_risk_high");
        assertThat(signals.find(signal.id()).orElseThrow().status()).isEqualTo(SignalStatus.ACTIVE);
        assertThat(auditFor(AuditEventType.AUTO_HELD, signal.id())).singleElement()
                .satisfies(a -> assertThat(a.payload()).containsEntry("outcome", "HELD").containsEntry("eventRisk", "HIGH"));
        assertThat(auditFor(AuditEventType.APPROVAL_CREATED, signal.id())).hasSize(1);
        assertThat(entryIntents(signal.id())).isZero();

        // offered again (the sweep), it is held again without a second approval
        assertThat(auto.onSignal(signal.id()).outcome()).isEqualTo(AutoDecision.Outcome.HELD);
        assertThat(pendingFor(signal.id())).hasSize(1);

        // a human approves in the inbox: the signal executes through the confirmation path
        fake.injectQuote(infy, "1507.50");
        HttpHeaders headers = bearer(token);
        headers.set("Idempotency-Key", "auto-held-approve");
        ResponseEntity<Map> approved = rest.exchange("/api/v1/approvals/" + approval.id() + "/approve", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertThat(approved.getStatusCode()).as(String.valueOf(approved.getBody())).isEqualTo(HttpStatus.OK);
        fake.flush();
        awaitPosition(p -> p.status() == PositionStatus.OPEN && p.stopOrderId() != null);
        assertThat(auditFor(AuditEventType.USER_APPROVED, signal.id())).isNotEmpty();
        assertThat(auditFor(AuditEventType.AUTO_EXECUTED, signal.id())).isEmpty();
    }

    @Test
    void theKillSwitchStopsAutoAtOnce() throws Exception {
        deploy(4, Map.of());
        risk.activate(ExecutionMode.PAPER, KillSwitchAction.STOP_NEW_ORDERS, null, "admin");
        openingRangeAndBreakout();
        Signal signal = awaitSignal();
        AuditRecord refused = awaitAudit(AuditEventType.AUTO_HELD, signal.id());
        assertThat(refused.payload()).containsEntry("outcome", "DENIED");
        assertThat((String) refused.payload().get("reason")).contains("kill switch");
        assertThat(entryIntents(signal.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_intent WHERE signal_id = ?", Integer.class, signal.id())).isZero();
    }

    @Test
    void autonomyFourLeavesAReentryToAHuman() throws Exception {
        deploy(4, Map.of());
        earlierEntryToday();
        openingRangeAndBreakout();
        Signal signal = awaitSignal();
        AuditRecord held = awaitAudit(AuditEventType.AUTO_HELD, signal.id());
        assertThat(held.payload()).containsEntry("outcome", "HELD");
        assertThat((String) held.payload().get("reason")).contains("autonomy 4 automates the first entry");
        await(() -> pendingFor(signal.id()).stream().findFirst(), "an approval");
        assertThat(entryIntents(signal.id())).isZero();
    }

    @Test
    void autonomyFiveReentersWithinTheDailyBudget() throws Exception {
        deploy(5, Map.of("daily_max_trades", 2));
        earlierEntryToday();
        openingRangeAndBreakout();
        Signal signal = awaitSignal();
        awaitStatus(signal.id(), SignalStatus.EXECUTED);
        // the signal is marked EXECUTED just before the audit row is written: wait for the row
        assertThat(awaitAudit(AuditEventType.AUTO_EXECUTED, signal.id()).payload()).containsEntry("entriesToday", 1).containsEntry("autonomyLevel", 5);
    }

    @Test
    void aSpentDailyBudgetBlocksTheSignal() throws Exception {
        deploy(5, Map.of("daily_max_trades", 1));
        earlierEntryToday();
        openingRangeAndBreakout();
        Signal signal = awaitSignal();
        Signal blocked = awaitStatus(signal.id(), SignalStatus.BLOCKED);
        assertThat(blocked.note()).contains("deployment_budget").contains("1 of its 1 entries");
        assertThat(awaitAudit(AuditEventType.AUTO_HELD, signal.id()).payload()).containsEntry("outcome", "DENIED").containsEntry("rule", "deployment_budget");
        assertThat(entryIntents(signal.id())).isZero();
        assertThat(pendingFor(signal.id())).isEmpty();
    }

    @Test
    void aRestartMidTradeRestoresTheStopAndNeverEntersTwice() throws Exception {
        deploy(4, Map.of());
        openingRangeAndBreakout();
        Signal signal = awaitSignal();
        awaitStatus(signal.id(), SignalStatus.EXECUTED);
        fake.flush();
        StrategyPosition open = awaitPosition(p -> p.status() == PositionStatus.OPEN && p.stopOrderId() != null);
        UUID firstStop = open.stopOrderId();

        // the server dies mid-trade: the engine stops, the broker loses the stop, the server comes back
        engine.stop();
        execution.cancel(firstStop);
        fake.flush();
        assertThat(orders.findById(firstStop).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);
        engine.start();
        StrategyPosition restored = awaitPosition(p -> p.status() == PositionStatus.OPEN && p.stopOrderId() != null && !firstStop.equals(p.stopOrderId()));
        assertThat(orders.findById(restored.stopOrderId()).orElseThrow().state().isLive()).isTrue();
        assertThat(engine.runner(deployment.id(), infy).orElseThrow().position()).isPresent();

        // the startup sweep, a redelivered signal event and a direct re-offer all find nothing to do
        assertThat(auto.sweep()).isEmpty();
        publisher.publishEvent(new SignalGeneratedEvent(EventMeta.create(hejjeClock), signal.id(), signal.versionId(), signal.instrumentId()));
        Thread.sleep(300);
        assertThat(auto.onSignal(signal.id()).outcome()).isEqualTo(AutoDecision.Outcome.SKIPPED);
        assertThat(entryIntents(signal.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM strategy_position WHERE signal_id = ?", Integer.class, signal.id())).isEqualTo(1);
        assertThat(auditFor(AuditEventType.AUTO_EXECUTED, signal.id())).hasSize(1);
    }

    @Test
    void anLlmOutageHasNoEffectOnAuto() throws Exception {
        FixtureLlmProvider fixture = (FixtureLlmProvider) llm.provider("fixture").orElseThrow();
        fixture.responder(r -> {
            throw new IllegalStateException("provider down");
        });
        try {
            int calls = jdbc.queryForObject("SELECT count(*) FROM llm_call", Integer.class);
            deploy(4, Map.of());
            openingRangeAndBreakout();
            Signal signal = awaitSignal();
            awaitStatus(signal.id(), SignalStatus.EXECUTED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_call", Integer.class)).isEqualTo(calls);
        } finally {
            fixture.reset();
        }
    }

    @Test
    void autonomyFourAndFiveNeedAnAutoOrPaperDeploymentAndPaperHistoryForLive() {
        jdbc.update("UPDATE strategy_version SET status = 'LIVE' WHERE id = ?", version.id());
        UUID strategyId = version.strategyId();
        assertThatThrownBy(() -> strategies.deploy(strategyId, 1, ExecutionMode.CONFIRM, List.of("NSE:INFY"), 4, Map.of(), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CONFIRM");
        assertThatThrownBy(() -> strategies.deploy(strategyId, 1, ExecutionMode.PAPER, List.of("NSE:INFY"), 6, Map.of(), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("between 0 and 5");
        assertThatThrownBy(() -> strategies.deploy(strategyId, 1, ExecutionMode.AUTO, List.of("NSE:INFY"), 4, Map.of(), "admin"))
                .isInstanceOf(StrategyException.Conflict.class).hasMessageContaining("0 closed paper trade(s)").hasMessageContaining("never automatic");
        paperTrades(29);
        assertThatThrownBy(() -> strategies.deploy(strategyId, 1, ExecutionMode.AUTO, List.of("NSE:INFY"), 5, Map.of(), "admin"))
                .isInstanceOf(StrategyException.Conflict.class).hasMessageContaining("29 closed");
        paperTrades(1);
        assertThat(strategies.deploy(strategyId, 1, ExecutionMode.AUTO, List.of("NSE:INFY"), 5, Map.of(), "admin").autonomyLevel()).isEqualTo(5);
        assertThat(strategies.deploy(strategyId, 1, ExecutionMode.CONFIRM, List.of("NSE:INFY"), 3, Map.of(), "admin").autonomyLevel()).isEqualTo(3);
    }
}
