package money.hejje.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.time.MutableClock;
import money.hejje.events.EventService;
import money.hejje.events.EventType;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.OrderReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Performance investigation end to end (plan M4.5): trade context captured at entry, new P&L dimensions, attribution, counterfactual, tools. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PerformanceIT extends AbstractIntegrationTest {

    static final String EVENT_TITLE = "Q2 results (PerformanceIT)";

    @Autowired ExecutionEngine engine;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired EventService events;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;

    UUID infy;
    String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade_review, trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear();
        clock.setIst("2026-09-08T09:50:00");
        events.add(EventType.RESULTS, "NSE:INFY", EVENT_TITLE, LocalDate.parse("2026-09-08"), null, null, 1.0, "it");
        // a news-bias snapshot five minutes before the first entry: the review records it as the trade's news context; snapshots
        // other suites computed for INFY that morning (their clocks share the date) would be newer, so they are cleared first
        jdbc.update("DELETE FROM news_bias WHERE instrument_id = ? AND computed_at > '2026-09-08T00:00:00Z' AND computed_at <= '2026-09-08T08:00:00Z'", infy);
        jdbc.update("INSERT INTO news_bias (id, instrument_id, computed_at, score, label, items, evidence) VALUES (?, ?, '2026-09-08T04:25:00Z', -0.45, 'BEARISH', 2, '[]')",
                UUID.randomUUID(), infy);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM market_event WHERE title = ?", EVENT_TITLE);
        jdbc.update("DELETE FROM news_bias WHERE instrument_id = ? AND computed_at = '2026-09-08T04:25:00Z'", infy);
        clock.set(java.time.Instant.now());
    }

    OrderIntentCommand manual(Side side, int qty, Price stop) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, infy, side, Quantity.of(qty), OrderType.MARKET,
                Product.MIS, null, null, stop, null, null, side == Side.BUY ? OrderReason.MANUAL : OrderReason.POSITION_CLOSE);
    }

    void roundTrip(String entryAt, String entry, String exitAt, String exit) {
        clock.setIst(entryAt);
        fake.injectQuote(infy, entry);
        engine.submit(manual(Side.BUY, 10, Price.of(new java.math.BigDecimal(entry).subtract(java.math.BigDecimal.TEN))));
        fake.flush();
        clock.setIst(exitAt);
        fake.injectQuote(infy, exit);
        engine.submit(manual(Side.SELL, 10, null));
        fake.flush();
    }

    Map<String, Object> get(String path) {
        ResponseEntity<Map> r = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s %s", path, r.getBody()).isTrue();
        return r.getBody();
    }

    @Test
    void contextAtEntryFeedsTheBreakdownsAttributionAndCounterfactual() throws Exception {
        roundTrip("2026-09-08T10:00:00", "1500.00", "2026-09-08T10:20:00", "1490.00");   // a loss on a results day with bearish news
        roundTrip("2026-09-08T11:00:00", "1500.00", "2026-09-08T11:30:00", "1520.00");   // a win, same context
        awaitAsyncListeners();
        token = adminAccessToken(); // after moving the clock: access tokens live 15 minutes

        List<Map<String, Object>> reviews = jdbc.queryForList("SELECT context::text AS ctx FROM trade_review ORDER BY opened_at");
        assertThat(reviews).hasSize(2);
        assertThat((String) reviews.get(0).get("ctx")).contains("\"event\": \"HIGH\"").contains("\"news\": \"BEARISH\"").contains(EVENT_TITLE);

        String period = "from=2026-09-01&to=2026-09-08";
        List<Map<String, Object>> byEvent = (List<Map<String, Object>>) get("/api/v1/analytics/pnl?groupBy=eventContext&" + period).get("buckets");
        assertThat(byEvent).singleElement().satisfies(b -> assertThat(b).containsEntry("key", "HIGH").containsEntry("trades", 2));
        List<Map<String, Object>> byNews = (List<Map<String, Object>>) get("/api/v1/analytics/pnl?groupBy=newsBias&" + period).get("buckets");
        assertThat(byNews).singleElement().satisfies(b -> assertThat(b).containsEntry("key", "BEARISH"));
        assertThat((List<Map<String, Object>>) get("/api/v1/analytics/pnl?groupBy=family&" + period).get("buckets"))
                .singleElement().satisfies(b -> assertThat(b).containsEntry("key", "MANUAL"));

        Map<String, Object> losses = get("/api/v1/analytics/losses?" + period);
        Map<String, Object> attribution = (Map<String, Object>) losses.get("attribution");
        assertThat(attribution).containsEntry("trades", 2).containsEntry("losers", 1).containsEntry("winners", 1);
        Map<String, Object> eventDim = ((List<Map<String, Object>>) attribution.get("dimensions")).stream().filter(d -> d.get("name").equals("event")).findFirst().orElseThrow();
        assertThat(((List<Map<String, Object>>) eventDim.get("buckets")).get(0)).containsEntry("key", "HIGH").containsEntry("lossSharePct", 100.0);
        assertThat(get("/api/v1/analytics/slippage?" + period)).containsKey("slippage");
        assertThat((Map<String, Object>) get("/api/v1/analytics/adherence?" + period).get("adherence")).containsEntry("trades", 2);

        ResponseEntity<Map> cf = rest.exchange("/api/v1/analytics/counterfactual", HttpMethod.POST,
                new HttpEntity<>(Map.of("from", "2026-09-01", "to", "2026-09-08", "exclude", Map.of("events", List.of("HIGH"))), bearer(token)), Map.class);
        Map<String, Object> counterfactual = (Map<String, Object>) cf.getBody().get("counterfactual");
        assertThat(counterfactual).containsEntry("basis", "SIMULATED").containsEntry("excludedTrades", 2);
        assertThat((Map<String, Object>) counterfactual.get("actual")).containsEntry("trades", 2);
        assertThat((Map<String, Object>) counterfactual.get("simulated")).containsEntry("trades", 0);
        assertThat(rest.exchange("/api/v1/analytics/counterfactual", HttpMethod.POST, new HttpEntity<>(Map.of("exclude", Map.of()), bearer(token)), Map.class)
                .getStatusCode().value()).isEqualTo(400);

        // the agent tools default to month to date (the clock is on 2026-09-08)
        Map<String, Object> tool = rest.exchange("/api/v1/agents/tools/get_loss_attribution", HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(token)), Map.class)
                .getBody();
        assertThat((Map<String, Object>) tool.get("output")).containsEntry("from", "2026-09-01").containsEntry("to", "2026-09-08");
        Map<String, Object> simulated = rest.exchange("/api/v1/agents/tools/run_counterfactual", HttpMethod.POST,
                new HttpEntity<>(Map.of("exclude", Map.of("news", List.of("BEARISH"))), bearer(token)), Map.class).getBody();
        assertThat((Map<String, Object>) ((Map<String, Object>) simulated.get("output")).get("counterfactual")).containsEntry("basis", "SIMULATED");
    }
}
