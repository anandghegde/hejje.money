package money.hejje.events;

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
import money.hejje.common.event.TickBus;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import money.hejje.market.internal.InProcessTickBus;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalEngine;
import money.hejje.signals.SignalService;
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

/**
 * A strategy with {@code event_rules} and results today: block yields AVOID on Today and a deterministic risk rejection
 * of a manual execute; caution yields TRADE WITH CAUTION. Mirrors SignalsIT's engine set-up on a different session.
 */
class EventRulesIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 1); // Tuesday, no curated events
    static final String YAML = """
            name: it_event_rules_orb
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
            event_rules:
              high_risk_event_within_minutes: 480
              action: %s
            """;

    @Autowired StrategyService strategies;
    @Autowired SignalService signals;
    @Autowired SignalEngine engine;
    @Autowired InstrumentService instruments;
    @Autowired EventService events;
    @Autowired FakeBrokerAdapter fake;
    @Autowired TickBus bus;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;

    UUID infy;
    String token;

    @BeforeEach
    void setUp() {
        engine.stop();
        jdbc.execute("TRUNCATE market_event, strategy_position, signal, strategy_score, backtest_trade, backtest, strategy_deployment, strategy_version, strategy, "
                + "trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        jdbc.update("UPDATE risk_limits SET max_loss_per_day_paise = 500000, max_realized_loss_paise = 500000, max_total_loss_paise = 750000, "
                + "max_margin_utilization_pct = 80.00, max_open_positions = 5, max_trades_per_day = 20, max_risk_per_trade_paise = 200000, "
                + "max_quantity = 1000, max_notional_paise = 50000000, min_reward_risk = 1.00, mandatory_stop = TRUE, max_stop_distance_pct = 5.00, "
                + "no_new_trades_after = '14:45', no_averaging_down = TRUE, no_reentry_minutes = 10, max_consecutive_losses = 3 WHERE mode = 'PAPER'");
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear();
        pipeline.flush();
        jdbc.execute("TRUNCATE market_candle");
        token = adminAccessToken();
        clock.setIst(DAY + "T09:36:00");
        // results this afternoon: event risk HIGH for INFY all day
        events.add(EventType.RESULTS, "NSE:INFY", "Q2 results", DAY, LocalTime.of(16, 0), null, 1.0, "admin");
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        clock.set(java.time.Instant.now());
    }

    @Test
    void blockRuleYieldsAvoidAndARiskRejection() throws Exception {
        Signal signal = signalFor("block");
        ResponseEntity<Map> today = rest.exchange("/api/v1/today", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        Map<?, ?> r = ((List<Map<?, ?>>) today.getBody().get("ranked")).get(0);
        assertThat(r.get("decision")).isEqualTo("AVOID");
        assertThat(r.get("eventRisk")).isEqualTo("HIGH");
        assertThat(r.get("nextEvent")).isEqualTo("Q2 results — Today 16:00");
        assertThat((List<String>) r.get("hardBlocks")).anySatisfy(b -> assertThat(b).startsWith("eventRule:").contains("Q2 results in").contains("rule: block within 480 min"));
        assertThat(today.getBody().get("best")).isNull();

        // the pipeline rejects a manual execute deterministically
        HttpHeaders h = bearer(token);
        h.set("Idempotency-Key", "ev-1");
        ResponseEntity<Map> executed = rest.exchange("/api/v1/signals/" + signal.id() + "/execute", HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(executed.getStatusCode().is4xxClientError()).as(String.valueOf(executed.getBody())).isTrue();
        assertThat(String.valueOf(executed.getBody())).contains("eventRule");
        assertThat(signals.find(signal.id()).orElseThrow().status().name()).isNotEqualTo("EXECUTED");
    }

    @Test
    void cautionRuleYieldsTradeWithCaution() throws Exception {
        Signal signal = signalFor("caution");
        jdbc.update("INSERT INTO strategy_score (id, version_id, instrument_id, computed_at, base_backtest_id, base, cap, components, adjustments, final) "
                + "VALUES (?, ?, ?, now(), NULL, 80, NULL, '[]', '[]', 88)", UUID.randomUUID(), signal.versionId(), infy);
        ResponseEntity<Map> today = rest.exchange("/api/v1/today", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        Map<?, ?> best = (Map<?, ?>) today.getBody().get("best");
        assertThat(best).isNotNull();
        assertThat(best.get("decision")).isEqualTo("TRADE_WITH_CAUTION");
        assertThat((List<String>) best.get("risks")).anySatisfy(x -> assertThat(x).startsWith("⚠ Q2 results").contains("rule: caution"));
        assertThat((List<String>) best.get("hardBlocks")).isEmpty();
        ResponseEntity<Map> prepared = rest.exchange("/api/v1/signals/" + signal.id() + "/prepare", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(prepared.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> checks = (List<Map<?, ?>>) ((Map<?, ?>) prepared.getBody().get("risk")).get("checks");
        assertThat(checks).anySatisfy(c -> {
            assertThat(c.get("name")).isEqualTo("eventRule");
            assertThat(c.get("passed")).isEqualTo(true);
        });
    }

    private Signal signalFor(String action) throws InterruptedException {
        StrategyVersion version = strategies.create(YAML.formatted(action), null, "admin");
        jdbc.update("UPDATE strategy_version SET status = 'PAPER' WHERE id = ?", version.id());
        StrategyDeployment deployment = strategies.deploy(version.strategyId(), 1, ExecutionMode.PAPER, List.of("NSE:INFY"), 0, Map.of("risk_rupees", 2000), "admin");
        engine.start();
        engine.refresh();
        awaitAsyncListeners();
        assertThat(engine.runner(deployment.id(), infy)).isPresent();
        fake.injectQuote(infy, "1507.50");
        bar("09:15", "1500", "1505", "1495", "1500");
        bar("09:20", "1500", "1503", "1497", "1501");
        bar("09:25", "1501", "1504", "1498", "1502");
        bar("09:30", "1502", "1508", "1501", "1507");
        List<Signal> active = signals.active();
        assertThat(active).hasSize(1);
        return active.get(0);
    }

    private void bar(String time, String open, String high, String low, String close) {
        Candle candle = SyntheticSessions.session(infy, DAY, List.of(SyntheticSessions.bar(open, high, low, close)), 50_000).get(0);
        LocalTime t = LocalTime.parse(time);
        Candle shifted = new Candle(infy, candle.timeframe(), DAY.atTime(t).atZone(SyntheticSessions.IST).toInstant(), candle.open(), candle.high(), candle.low(),
                candle.close(), candle.volume(), 0, false);
        bus.publish(new CandleClosedEvent(shifted));
        ((InProcessTickBus) bus).drain();
    }
}
