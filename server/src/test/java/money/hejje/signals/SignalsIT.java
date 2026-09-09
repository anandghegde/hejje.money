package money.hejje.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.backtest.SyntheticSessions;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import money.hejje.market.internal.InProcessTickBus;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderRole;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class SignalsIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 8);
    static final String YAML = """
            name: it_signals_orb
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
            max_trades_per_day: 1
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
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;

    UUID infy;
    String token;
    StrategyVersion version;
    StrategyDeployment deployment;

    @BeforeEach
    void setUp() {
        engine.stop();
        jdbc.execute("TRUNCATE strategy_position, signal, strategy_score, backtest_trade, backtest, strategy_deployment, strategy_version, strategy, "
                + "trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        jdbc.update("UPDATE risk_limits SET max_loss_per_day_paise = 500000, max_realized_loss_paise = 500000, max_total_loss_paise = 750000, "
                + "max_margin_utilization_pct = 80.00, max_open_positions = 5, max_trades_per_day = 20, max_risk_per_trade_paise = 200000, "
                + "max_quantity = 1000, max_notional_paise = 50000000, min_reward_risk = 1.00, mandatory_stop = TRUE, max_stop_distance_pct = 5.00, "
                + "no_new_trades_after = '14:45', no_averaging_down = TRUE, no_reentry_minutes = 10, max_consecutive_losses = 3 WHERE mode = 'PAPER'");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear(); // other suites leave newer-stamped quotes behind; the cache ignores older ticks
        pipeline.flush(); // close bars other suites left in the candle builder (before any runner exists)
        jdbc.execute("TRUNCATE market_candle"); // recent candles from other suites would warm the runner past the bars published here
        token = adminAccessToken();
        // the clock sits just after the signal bar closes for the whole test; candles are published with their own times,
        // and quotes never cross a minute boundary so the candle builder never emits competing candles
        clock.setIst(DAY + "T09:36:00");
        version = strategies.create(YAML, null, "admin");
        jdbc.update("UPDATE strategy_version SET status = 'PAPER' WHERE id = ?", version.id());
        deployment = strategies.deploy(version.strategyId(), 1, ExecutionMode.PAPER, List.of("NSE:INFY"), 0, Map.of("risk_rupees", 2000), "admin");
        engine.start();
        engine.refresh();
        assertThat(engine.runner(deployment.id(), infy)).isPresent();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        clock.set(java.time.Instant.now());
    }

    private void bar(String time, String open, String high, String low, String close) {
        Candle candle = SyntheticSessions.session(infy, DAY, List.of(SyntheticSessions.bar(open, high, low, close)), 50_000).get(0);
        LocalTime t = LocalTime.parse(time);
        Candle shifted = new Candle(infy, candle.timeframe(), DAY.atTime(t).atZone(SyntheticSessions.IST).toInstant(), candle.open(), candle.high(), candle.low(),
                candle.close(), candle.volume(), 0, false);
        bus.publish(new CandleClosedEvent(shifted));
        ((InProcessTickBus) bus).drain();
    }

    /** Opening range 1495..1505 then a breakout close at 1507 on the 09:30 bar (signal at 09:35); the quote follows the close. */
    private void openingRangeAndBreakout() {
        fake.injectQuote(infy, "1507.50"); // fresh quote at the clock time: market data readiness stays green
        bar("09:15", "1500", "1505", "1495", "1500");
        bar("09:20", "1500", "1503", "1497", "1501");
        bar("09:25", "1501", "1504", "1498", "1502");
        bar("09:30", "1502", "1508", "1501", "1507");
    }

    private HttpHeaders idem(String key) {
        HttpHeaders h = bearer(token);
        h.set("Idempotency-Key", key);
        return h;
    }

    private StrategyPosition awaitPosition(java.util.function.Predicate<StrategyPosition> condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<StrategyPosition> live = signals.positions(false);
            if (!live.isEmpty() && condition.test(live.get(0))) {
                return live.get(0);
            }
            Thread.sleep(100);
        }
        List<StrategyPosition> all = signals.positions(false);
        String orderState = all.isEmpty() || all.get(0).entryOrderId() == null ? "no entry order" : String.valueOf(orders.findById(all.get(0).entryOrderId()).orElse(null));
        throw new AssertionError("position did not reach the expected state: " + all + " entry order: " + orderState);
    }

    @Test
    void signalPrepareExecuteStopPlacedAndStopFill() throws Exception {
        openingRangeAndBreakout();
        ResponseEntity<List> active = rest.exchange("/api/v1/signals/active", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(active.getBody()).hasSize(1);
        Map<?, ?> s = (Map<?, ?>) active.getBody().get(0);
        UUID signalId = UUID.fromString((String) s.get("id"));
        assertThat(s.get("side")).isEqualTo("BUY");
        assertThat(s.get("referencePrice")).isEqualTo(1507.0);
        assertThat(s.get("stop")).isEqualTo(1495.0);
        assertThat(s.get("target")).isEqualTo(1531.0);
        assertThat(s.get("barTime")).isEqualTo(DAY.atTime(9, 35).atZone(SyntheticSessions.IST).toInstant().toString());
        assertThat(s.get("validUntil")).isEqualTo(DAY.atTime(9, 40).atZone(SyntheticSessions.IST).toInstant().toString());
        assertThat((List<?>) s.get("evidence")).hasSize(1);

        fake.injectQuote(infy, "1507.50"); // the market ticked up since the bar closed: sizing uses the live quote
        ResponseEntity<Map> prepared = rest.exchange("/api/v1/signals/" + signalId + "/prepare", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(prepared.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> proposal = (Map<?, ?>) prepared.getBody().get("proposal");
        assertThat(proposal.get("quantity")).isEqualTo(160); // floor(2000 / 12.50)
        assertThat(proposal.get("orderType")).isEqualTo("MARKET");
        assertThat(proposal.get("stopPrice")).isEqualTo("1495.00");
        assertThat(((Map<?, ?>) prepared.getBody().get("risk")).get("outcome")).as(String.valueOf(((Map<?, ?>) prepared.getBody().get("risk")).get("checks")))
                .isEqualTo("APPROVED");
        assertThat(((Map<?, ?>) prepared.getBody().get("signal")).get("status")).isEqualTo("PREPARED");

        ResponseEntity<Map> executed = rest.exchange("/api/v1/signals/" + signalId + "/execute", HttpMethod.POST, new HttpEntity<>(idem("k-1")), Map.class);
        assertThat(executed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID entryOrderId = UUID.fromString((String) executed.getBody().get("id"));
        assertThat(signals.find(signalId).orElseThrow().status()).isEqualTo(SignalStatus.EXECUTED);
        // replaying the same key returns the same order
        ResponseEntity<Map> replay = rest.exchange("/api/v1/signals/" + signalId + "/execute", HttpMethod.POST, new HttpEntity<>(idem("k-1")), Map.class);
        assertThat(replay.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);

        fake.flush();
        StrategyPosition open = awaitPosition(p -> p.status() == PositionStatus.OPEN && p.stopOrderId() != null);
        assertThat(open.entryOrderId()).isEqualTo(entryOrderId);
        assertThat(open.quantity()).isEqualTo(160);
        assertThat(open.entryPrice()).isEqualByComparingTo("1507.50");
        HejjeOrder stop = orders.findById(open.stopOrderId()).orElseThrow();
        assertThat(stop.role()).isEqualTo(OrderRole.STOP);
        assertThat(stop.orderType()).isEqualTo(OrderType.SL_M);
        assertThat(stop.triggerPrice()).isEqualByComparingTo("1495.00");
        assertThat(stop.state().isLive()).isTrue();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.STRATEGY_STOP_PLACED, null, 0, 20)).content()).isNotEmpty();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.USER_APPROVED, null, 0, 20)).content()).anyMatch(r -> signalId.equals(r.signalId()));

        // the stop triggers at the broker: position closes with reason STOP and the chain is audited
        fake.injectQuote(infy, "1494.00");
        fake.flush();
        StrategyPosition closed = awaitPosition(p -> p.status() == PositionStatus.CLOSED);
        assertThat(closed.closeReason()).isEqualTo(CloseReason.STOP);
        assertThat(closed.exitPrice()).isEqualByComparingTo("1494.00");
        assertThat(engine.runner(deployment.id(), infy).orElseThrow().position()).isEmpty();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.POSITION_CLOSED, null, 0, 20)).content()).anyMatch(r -> signalId.equals(r.signalId()));

        // max_trades_per_day = 1: another breakout bar produces no new signal today
        bar("09:35", "1507", "1512", "1506", "1511");
        assertThat(signals.active()).isEmpty();
        assertThat(engine.runner(deployment.id(), infy).orElseThrow().tradesToday()).isEqualTo(1);
    }

    @Test
    void signalExpiresWhenNotExecutedBeforeTheNextBarCloses() {
        openingRangeAndBreakout();
        Signal s = signals.active().get(0);
        bar("09:35", "1507", "1509", "1505", "1508");
        assertThat(signals.find(s.id()).orElseThrow().status()).isEqualTo(SignalStatus.EXPIRED);
        ResponseEntity<Map> prepare = rest.exchange("/api/v1/signals/" + s.id() + "/prepare", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(prepare.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // still within the window, still under max trades: the runner signals again on the next qualifying bar
        bar("09:40", "1508", "1510", "1506", "1509");
        assertThat(signals.active()).hasSize(1);
        Signal second = signals.active().get(0);
        ResponseEntity<Map> skipped = rest.exchange("/api/v1/signals/" + second.id() + "/skip", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "not today"), bearer(token)), Map.class);
        assertThat(skipped.getBody().get("status")).isEqualTo("SKIPPED");
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.SIGNAL_SKIPPED, null, 0, 20)).content()).anyMatch(r -> second.id().equals(r.signalId()));
    }

    @Test
    void forceExitAt1510AndRestartReattachesTheStop() throws Exception {
        openingRangeAndBreakout();
        List<Signal> active = signals.active();
        assertThat(active).as("signals: " + signals.list(null, null, 20)).hasSize(1);
        UUID signalId = active.get(0).id();
        fake.injectQuote(infy, "1507.50");
        ResponseEntity<Map> executed = rest.exchange("/api/v1/signals/" + signalId + "/execute", HttpMethod.POST, new HttpEntity<>(idem("k-2")), Map.class);
        assertThat(executed.getStatusCode()).as(String.valueOf(executed.getBody())).isEqualTo(HttpStatus.CREATED);
        fake.flush();
        StrategyPosition open = awaitPosition(p -> p.status() == PositionStatus.OPEN && p.stopOrderId() != null);
        UUID firstStop = open.stopOrderId();

        // simulate a crash: the engine stops, the broker loses the stop, the server comes back
        engine.stop();
        execution.cancel(firstStop);
        fake.flush();
        assertThat(orders.findById(firstStop).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);
        engine.start();
        StrategyPosition restored = awaitPosition(p -> p.status() == PositionStatus.OPEN && p.stopOrderId() != null && !firstStop.equals(p.stopOrderId()));
        assertThat(engine.runner(deployment.id(), infy).orElseThrow().position()).isPresent();
        assertThat(orders.findById(restored.stopOrderId()).orElseThrow().state().isLive()).isTrue();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.STOP_MISSING, null, 0, 20)).content()).isNotEmpty();

        // drift sideways until the bar closing at 15:10 forces the exit
        for (LocalTime t = LocalTime.of(9, 35); t.isBefore(LocalTime.of(15, 10)); t = t.plusMinutes(5)) {
            bar(t.toString(), "1508", "1509", "1507", "1508");
            fake.injectQuote(infy, "1508.00");
        }
        fake.flush();
        StrategyPosition closed = awaitPosition(p -> p.status() == PositionStatus.CLOSED);
        assertThat(closed.closeReason()).isEqualTo(CloseReason.FORCE_EXIT);
        assertThat(closed.exitOrderId()).isNotNull();
        assertThat(orders.findById(closed.exitOrderId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
        assertThat(orders.findById(restored.stopOrderId()).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.STRATEGY_EXIT_TRIGGERED, null, 0, 20)).content()).anyMatch(r -> signalId.equals(r.signalId()));
    }

    @Test
    void pausingTheDeploymentStopsNewSignals() {
        strategies.updateDeployment(deployment.id(), false, "pause", "admin");
        engine.refresh();
        openingRangeAndBreakout();
        assertThat(signals.active()).isEmpty();
        strategies.updateDeployment(deployment.id(), true, null, "admin");
        engine.refresh();
        bar("09:35", "1507", "1512", "1506", "1511");
        assertThat(signals.active()).hasSize(1);
    }
}
