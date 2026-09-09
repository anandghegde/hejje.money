package money.hejje.context;

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
import money.hejje.llm.FixtureLlmProvider;
import money.hejje.llm.LlmService;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import money.hejje.market.internal.InProcessTickBus;
import money.hejje.news.NewsService;
import money.hejje.news.NewsSource;
import money.hejje.news.internal.FeedParser;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cautions and the Context Card end to end: a scored signal whose reward:risk at the quote slips under the strategy floor,
 * opposing news from the fixture LLM and a stale quote → TRADE WITH CAUTION as Best Hejje with three cautions; the
 * card rows and {@code GET /context/strategy}. Mirrors SignalsIT's engine set-up on its own session.
 */
class ContextIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 2); // Wednesday, no curated events
    static final String YAML = """
            name: it_context_orb
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
            risk_overrides:
              min_reward_risk: 1.9
            """;

    @Autowired StrategyService strategies;
    @Autowired SignalService signals;
    @Autowired SignalEngine engine;
    @Autowired InstrumentService instruments;
    @Autowired NewsService news;
    @Autowired LlmService llm;
    @Autowired FakeBrokerAdapter fake;
    @Autowired TickBus bus;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;

    UUID infy;
    String token;
    FixtureLlmProvider fixture;

    @BeforeEach
    void setUp() {
        engine.stop();
        jdbc.execute("TRUNCATE news_bias, news_assessment, news_item, news_source, market_event, strategy_position, signal, strategy_score, backtest_trade, backtest, "
                + "strategy_deployment, strategy_version, strategy, trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
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
        clock.setIst(DAY + "T09:36:00");
        token = adminAccessToken();
        fixture = (FixtureLlmProvider) llm.provider("fixture").orElseThrow();
        fixture.reset();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        fixture.reset();
        clock.set(java.time.Instant.now());
    }

    @Test
    void cautionsAndContextCard() throws Exception {
        // opposing news: a bearish story about INFY classified by the fixture LLM
        news.seedSource("Test wire", "https://ctx.invalid/rss", NewsSource.Kind.RSS, 0.9, true);
        UUID sourceId = news.sources().get(0).id();
        jdbc.update("UPDATE news_source SET last_polled_at = ?, last_error = NULL", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
        fixture.respondWhenContains("Title: Infosys loses", """
                {"assessments":[{"symbol":"NSE:INFY","relevance":0.9,"direction":-0.9,"materiality":0.8,"novelty":1.0,"confidence":0.9,"event_type":"CONTRACT",
                 "summary":"Infosys lost a key client."}]}
                """);
        news.ingest(sourceId, List.of(new FeedParser.Fetched("https://ctx/1", "Infosys loses key client", "The client moved to a rival.", clock.instant().minusSeconds(300))));

        StrategyVersion version = strategies.create(YAML, null, "admin");
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
        jdbc.update("INSERT INTO strategy_score (id, version_id, instrument_id, computed_at, base_backtest_id, base, cap, components, adjustments, final) "
                + "VALUES (?, ?, ?, now(), NULL, 80, NULL, '[]', '[]', 88)", UUID.randomUUID(), version.id(), infy);
        // the quote goes stale for the cache (5 s) but not for readiness (10 s): 7 s after the injected quote
        clock.setIst(DAY + "T09:36:07");

        ResponseEntity<Map> today = rest.exchange("/api/v1/today", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        Map<?, ?> best = (Map<?, ?>) today.getBody().get("best");
        assertThat(best).as(String.valueOf(today.getBody())).isNotNull();
        assertThat(best.get("decision")).isEqualTo("TRADE_WITH_CAUTION");
        List<Map<?, ?>> cautions = (List<Map<?, ?>>) best.get("cautions");
        assertThat(cautions).extracting(c -> (Object) c.get("code")).containsExactlyInAnyOrder("REWARD_RISK", "NEWS_OPPOSING", "MARKET_DATA_STALE");
        assertThat((List<String>) best.get("risks")).anySatisfy(r -> assertThat(r).startsWith("⚠ Reward:risk 1.88").contains("below the strategy's minimum 1.9")); // 23.5 / 12.5 at the 1507.50 quote
        assertThat((List<String>) best.get("risks")).anySatisfy(r -> assertThat(r).contains("opposes the long signal"));
        assertThat((List<String>) best.get("hardBlocks")).isEmpty();
        assertThat(((Number) best.get("newsBias")).doubleValue()).isLessThanOrEqualTo(-0.4);
        Map<?, ?> card = (Map<?, ?>) best.get("context");
        assertThat(card).isNotNull();
        List<Map<?, ?>> items = (List<Map<?, ?>>) card.get("items");
        assertThat(items).extracting(i -> (Object) i.get("name")).containsExactly("Technical fit", "Market regime", "News bias", "Event risk", "Sector");
        Map<?, ?> newsRow = items.get(2);
        assertThat(newsRow.get("status")).isEqualTo("RED");
        assertThat((String) newsRow.get("value")).startsWith("Bearish -0.");
        assertThat(items.get(3).get("status")).isEqualTo("GREEN"); // no events: LOW
        assertThat(items.get(1).get("status")).isEqualTo("UNKNOWN"); // no index history on this session
        assertThat((List<String>) best.get("risks")).anySatisfy(r -> assertThat(r).startsWith("⚠ News bias Bearish"));
        assertThat(((Number) card.get("netImpact")).intValue()).isEqualTo(((Number) newsRow.get("delta")).intValue()); // regime unknown, event 0, news negative

        ResponseEntity<Map> served = rest.exchange("/api/v1/context/strategy?versionId=" + version.id() + "&instrumentId=" + infy, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) served.getBody().get("items")).hasSize(5);
        assertThat(((Map<?, ?>) served.getBody().get("eventRisk")).get("value")).isEqualTo("Low");

        // a fresh quote and a strategy that accepts 2R: plain TRADE (news still opposes -> still a caution)
        clock.setIst(DAY + "T09:36:00");
        fake.injectQuote(infy, "1507.50");
        ResponseEntity<Map> again = rest.exchange("/api/v1/today", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        List<Map<?, ?>> codes = (List<Map<?, ?>>) ((Map<?, ?>) again.getBody().get("best")).get("cautions");
        assertThat(codes).extracting(c -> (Object) c.get("code")).doesNotContain("MARKET_DATA_STALE");
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
