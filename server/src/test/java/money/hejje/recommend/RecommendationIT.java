package money.hejje.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.backtest.SyntheticSessions;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ExecutionMode;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.risk.KillSwitchAction;
import money.hejje.risk.RiskService;
import money.hejje.signals.SignalEngine;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class RecommendationIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 8);
    static final String YAML = """
            name: it_today_orb
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
            """;

    @Autowired StrategyService strategies;
    @Autowired SignalEngine engine;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired RiskService risk;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;

    UUID infy;
    String token;

    @BeforeEach
    void setUp() {
        engine.stop();
        jdbc.execute("TRUNCATE recommendation, strategy_position, signal, strategy_score, backtest_trade, backtest, strategy_deployment, strategy_version, strategy, "
                + "trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear();
        pipeline.flush();
        jdbc.execute("TRUNCATE market_candle");
        token = adminAccessToken();
        clock.setIst(DAY + "T09:36:00");
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        clock.set(java.time.Instant.now());
    }

    private Map<?, ?> today() {
        ResponseEntity<Map> r = rest.exchange("/api/v1/today", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** Seeds the opening range and a breakout through the dev endpoint (published to runners, quoted into the cache). */
    private void breakout() {
        List<Map<String, Object>> candles = List.of(
                candle("09:15", "1500", "1505", "1495", "1500"), candle("09:20", "1500", "1503", "1497", "1501"),
                candle("09:25", "1501", "1504", "1498", "1502"), candle("09:30", "1502", "1508", "1501", "1507"));
        ResponseEntity<Map> r = rest.exchange("/api/v1/market/dev/candles", HttpMethod.POST, new HttpEntity<>(Map.of("instrumentId", infy.toString(),
                "timeframe", "M5", "candles", candles, "store", false, "publish", true, "quote", true), bearer(token)), Map.class); // store:false keeps the shared history clean for other suites
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        ((money.hejje.market.internal.InProcessTickBus) bus).drain();
    }

    @Autowired money.hejje.common.event.TickBus bus;

    private static Map<String, Object> candle(String time, String o, String h, String l, String c) {
        return Map.of("openTime", DAY.atTime(LocalTime.parse(time)).atZone(SyntheticSessions.IST).toInstant().toString(), "open", o, "high", h, "low", l, "close", c, "volume", 50000);
    }

    @Test
    void todayMovesFromNoDeploymentsToWaitToTradeToAvoid() throws InterruptedException {
        Map<?, ?> empty = today();
        assertThat((String) empty.get("noTrade")).contains("No strategies deployed");
        assertThat(empty.get("best")).isNull();
        assertThat(((Map<?, ?>) empty.get("header")).get("regime")).isEqualTo("UNKNOWN"); // no index history in this test

        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        strategies.changeStatus(v1.strategyId(), 1, VersionStatus.PAPER, "dev", "admin", true);
        assertThat(strategies.version(v1.strategyId(), 1).orElseThrow().status()).isEqualTo(VersionStatus.PAPER);
        StrategyDeployment d = strategies.deploy(v1.strategyId(), 1, ExecutionMode.PAPER, List.of("NSE:INFY"), 0, Map.of("risk_rupees", 2000), "admin");
        engine.refresh();
        awaitAsyncListeners(); // the DeploymentChanged listener restarts the runner asynchronously

        // deployed, no signal yet: WAIT
        Map<?, ?> waiting = today();
        List<Map<?, ?>> ranked = (List<Map<?, ?>>) waiting.get("ranked");
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).get("decision")).isEqualTo("WAIT");
        assertThat((String) waiting.get("noTrade")).contains("minimum quality threshold");

        // signal but no score: still WAIT with the reason
        breakout();
        Map<?, ?> unscored = today();
        Map<?, ?> r = ((List<Map<?, ?>>) unscored.get("ranked")).get(0);
        assertThat(r.get("decision")).isEqualTo("WAIT");
        assertThat(r.get("signalStatus")).isEqualTo("ACTIVE");
        assertThat((List<String>) r.get("risks")).anySatisfy(x -> assertThat(x).contains("no Hejje Score"));
        assertThat((List<String>) r.get("supportingEvidence")).anySatisfy(x -> assertThat(x).startsWith("✓ close > opening_range_high"));
        assertThat(r.get("quantity")).isEqualTo(166);
        assertThat(r.get("stop")).isEqualTo(1495.0);

        // a score above the threshold: TRADE, Best Hejje set, history recorded
        jdbc.update("INSERT INTO strategy_score (id, version_id, instrument_id, computed_at, base_backtest_id, base, cap, components, adjustments, final) "
                + "VALUES (?, ?, ?, now(), NULL, 80, NULL, '[]', '[]', 88)", UUID.randomUUID(), v1.id(), infy);
        Map<?, ?> trade = today();
        Map<?, ?> best = (Map<?, ?>) trade.get("best");
        assertThat(best).isNotNull();
        assertThat(best.get("decision")).isEqualTo("TRADE");
        assertThat(best.get("score")).isEqualTo(88);
        assertThat(best.get("direction")).isEqualTo("BUY");
        assertThat(best.get("instrument")).isEqualTo("NSE:INFY");
        assertThat(best.get("strategy")).isEqualTo("it_today_orb");
        assertThat(best.get("eventRisk")).isEqualTo("UNKNOWN");
        assertThat(best.get("expectedRewardRupees")).isNotNull();
        assertThat(trade.get("noTrade")).isNull();
        UUID signalId = UUID.fromString((String) best.get("signalId"));
        ResponseEntity<List> history = rest.exchange("/api/v1/today/history?signalId=" + signalId, HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(history.getBody()).extracting(h -> (Object) ((Map<?, ?>) h).get("decision")).containsExactly("WAIT", "TRADE");

        // kill switch: hard block -> AVOID
        risk.activate(ExecutionMode.PAPER, KillSwitchAction.STOP_NEW_ORDERS, null, "test");
        Map<?, ?> avoid = today();
        Map<?, ?> blocked = ((List<Map<?, ?>>) avoid.get("ranked")).get(0);
        assertThat(blocked.get("decision")).isEqualTo("AVOID");
        assertThat((List<String>) blocked.get("hardBlocks")).anySatisfy(x -> assertThat(x).startsWith("killSwitch"));
        assertThat(avoid.get("best")).isNull();
        assertThat(engine.runner(d.id(), infy)).isPresent();
    }

    @Test
    void forcedStatusIsAuditedAndRefusedWhenAlreadyThere() {
        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        ResponseEntity<Map> forced = rest.exchange("/api/v1/strategies/" + v1.strategyId() + "/versions/1/status", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "PAPER", "force", true, "note", "e2e"), bearer(token)), Map.class);
        assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(forced.getBody().get("status")).isEqualTo("PAPER");
        assertThat(rest.exchange("/api/v1/strategies/" + v1.strategyId() + "/versions/1/status", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "PAPER", "force", true), bearer(token)), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'STRATEGY_STATUS_CHANGED' AND payload->>'forced' = 'true' AND strategy_id = ?",
                Integer.class, v1.strategyId())).isEqualTo(1);
    }
}
