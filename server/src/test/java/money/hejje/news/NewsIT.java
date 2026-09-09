package money.hejje.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.llm.FixtureLlmProvider;
import money.hejje.llm.LlmService;
import money.hejje.news.internal.FeedParser;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoringService;
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

/** Ingest → dedupe → fixture-LLM classification → stored assessments → bias with evidence, endpoints, adjuster, staleness, and the call log. */
class NewsIT extends AbstractIntegrationTest {

    @Autowired NewsService news;
    @Autowired LlmService llm;
    @Autowired InstrumentService instruments;
    @Autowired StrategyService strategies;
    @Autowired ScoringService scoring;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    FixtureLlmProvider fixture;
    UUID infy;
    UUID tcs;
    UUID sourceId;
    String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE news_bias, news_assessment, news_item, news_source, llm_call, strategy_score, strategy_deployment, strategy_version, strategy CASCADE");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        fixture = (FixtureLlmProvider) llm.provider("fixture").orElseThrow();
        fixture.reset();
        news.seedSource("Test wire", "https://test.invalid/rss", NewsSource.Kind.RSS, 0.8, true);
        sourceId = news.sources().get(0).id();
        clock.setIst("2026-09-08T10:00:00");
        jdbc.update("UPDATE news_source SET last_polled_at = ?, last_error = NULL", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
        token = adminAccessToken();
    }

    @AfterEach
    void reset() {
        fixture.reset();
        clock.set(Instant.now());
    }

    @Test
    void ingestClassifiesAggregatesAndServes() {
        fixture.respondWhenContains("Title: Infosys wins", """
                {"assessments":[{"symbol":"NSE:INFY","relevance":0.9,"direction":0.8,"materiality":0.6,"novelty":1.0,"confidence":0.9,"event_type":"CONTRACT",
                 "summary":"Infosys won a large multi-year deal."}]}
                """);
        fixture.respondWhenContains("Title: TCS and Infosys", """
                {"assessments":[{"symbol":"NSE:TCS","relevance":0.7,"direction":-0.5,"materiality":0.4,"confidence":0.7,"event_type":"SECTOR","summary":"IT demand soft."},
                                {"symbol":"NSE:INFY","relevance":0.6,"direction":-0.4,"materiality":0.4,"confidence":0.7,"event_type":"SECTOR","summary":"IT demand soft."},
                                {"symbol":"NSE:NOPE","relevance":0.9,"direction":1,"materiality":1,"confidence":1}]}
                """);
        Instant now = clock.instant();
        NewsService.IngestResult r = news.ingest(sourceId, List.of(
                new FeedParser.Fetched("https://t/1", "Infosys wins $1.5 billion deal", "Infosys signed a multi-year contract.", now.minusSeconds(600)),
                new FeedParser.Fetched("https://t/1", "Infosys wins $1.5 billion deal", "Infosys signed a multi-year contract.", now.minusSeconds(600)), // same URL
                new FeedParser.Fetched("https://t/2", "Infosys wins $1.5 billion deal!", "Infosys signed a multi-year contract.", now.minusSeconds(500)), // same story
                new FeedParser.Fetched("https://t/3", "TCS and Infosys flag soft IT demand", "Analysts see weak deal wins.", now.minusSeconds(7200)),
                new FeedParser.Fetched("https://t/4", "Monsoon arrives in Kerala", "Weather news.", now.minusSeconds(100))));
        assertThat(r.stored()).isEqualTo(3);
        assertThat(r.duplicates()).isEqualTo(2);
        assertThat(r.assessed()).isEqualTo(3); // NOPE is not a candidate and is dropped
        assertThat(fixture.requests()).hasSize(2); // the weather item had no candidates: no LLM call
        assertThat(fixture.requests().get(0).profile()).isEqualTo("news");
        assertThat(fixture.requests().get(0).userPrompt()).contains("NSE:INFY: matched \"Infosys\"").contains("sector IT");

        NewsBias bias = news.bias(infy);
        assertThat(bias.available()).isTrue();
        assertThat(bias.items()).isEqualTo(2);
        assertThat(bias.score()).isGreaterThan(0.2);
        assertThat(bias.label()).isIn(NewsBiasLabel.BULLISH, NewsBiasLabel.STRONGLY_BULLISH);
        assertThat(bias.evidence().get(0)).startsWith("▲ Infosys won a large multi-year deal. (Test wire");
        assertThat(bias.evidence()).anySatisfy(e -> assertThat(e).startsWith("Price reaction: no session data yet"));
        assertThat(news.bias(tcs).label()).isIn(NewsBiasLabel.BEARISH, NewsBiasLabel.NEUTRAL);
        assertThat(news.bias(tcs).score()).isLessThan(0);

        ResponseEntity<Map> served = rest.exchange("/api/v1/context/news-bias?instrumentId=" + infy, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(served.getBody().get("label")).isEqualTo(bias.label().name());
        assertThat((List<?>) served.getBody().get("sources")).hasSize(2);
        ResponseEntity<List> items = rest.exchange("/api/v1/news?instrumentId=" + infy, HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(items.getBody()).hasSize(2);
        assertThat((List<?>) ((Map<?, ?>) items.getBody().get(0)).get("assessments")).isNotEmpty();
        ResponseEntity<List> all = rest.exchange("/api/v1/news", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(all.getBody()).hasSize(3);

        // the call log has hashes and tokens, never the prompt or a key
        List<Map<String, Object>> calls = jdbc.queryForList("SELECT * FROM llm_call");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).get("status")).isEqualTo("OK");
        assertThat(calls.get(0).get("prompt_version")).isEqualTo("news_classify_v1");
        assertThat(String.valueOf(calls.get(0))).doesNotContain("Infosys");

        // the adjuster
        StrategyVersion version = strategies.create("""
                name: it_news_orb
                universe: [NSE:INFY]
                timeframe: 5m
                direction: long
                entry:
                  all:
                    - close > opening_range_high
                stop:
                  type: opening_range_low
                """, null, "admin");
        Adjustment adj = scoring.compute(version.id(), infy).adjustments().stream().filter(a -> a.name().equals("News context")).findFirst().orElseThrow();
        assertThat(adj.delta()).isEqualTo((int) Math.round(3 * bias.score())).isBetween(1, 3);
        assertThat(adj.evidence().get(0)).startsWith("News bias " + bias.label());

        // sources endpoint and update
        ResponseEntity<List> sources = rest.exchange("/api/v1/news/sources", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(sources.getBody()).hasSize(1);
        ResponseEntity<Map> updated = rest.exchange("/api/v1/news/sources/" + sourceId, HttpMethod.PUT, new HttpEntity<>(Map.of("enabled", false, "reliability", 0.5), bearer(token)), Map.class);
        assertThat(updated.getBody().get("enabled")).isEqualTo(false);
        assertThat(updated.getBody().get("reliability")).isEqualTo(0.5);
    }

    @Test
    void staleFeedAndUnclassifiedItemsDegradeToNeutral() {
        // no successful poll in the last 2 h -> unavailable
        jdbc.update("UPDATE news_source SET last_polled_at = NULL");
        NewsBias stale = news.bias(infy);
        assertThat(stale.available()).isFalse();
        assertThat(stale.label()).isEqualTo(NewsBiasLabel.NEUTRAL);
        assertThat(stale.evidence().get(0)).startsWith("news stale");
        // classification failure (no fixture answer): the item is stored without an assessment, bias stays neutral
        jdbc.update("UPDATE news_source SET last_polled_at = ?", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
        NewsService.IngestResult r = news.ingest(sourceId, List.of(new FeedParser.Fetched("https://t/9", "Infosys announces buyback", "", clock.instant())));
        assertThat(r.stored()).isEqualTo(1);
        assertThat(r.assessed()).isZero();
        NewsBias neutral = news.bias(infy);
        assertThat(neutral.available()).isTrue();
        assertThat(neutral.items()).isZero();
        assertThat(neutral.evidence().get(0)).isEqualTo("No material news in the last 24 h");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_call WHERE status = 'FAILED'", Integer.class)).isEqualTo(1);
        Adjustment adj = scoring.compute(strategies.create("""
                name: it_news_neutral
                universe: [NSE:INFY]
                timeframe: 5m
                direction: long
                entry:
                  all:
                    - close > opening_range_high
                stop:
                  type: opening_range_low
                """, null, "admin").id(), infy).adjustments().stream().filter(a -> a.name().equals("News context")).findFirst().orElseThrow();
        assertThat(adj.delta()).isZero();
    }
}
