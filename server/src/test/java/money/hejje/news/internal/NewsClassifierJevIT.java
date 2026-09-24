package money.hejje.news.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.calibration.CalibrationService;
import money.hejje.common.time.MutableClock;
import money.hejje.events.EventService;
import money.hejje.events.EventType;
import money.hejje.events.MarketEvent;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.llm.FixtureJev;
import money.hejje.llm.FixtureLlmProvider;
import money.hejje.llm.JevTransport;
import money.hejje.llm.LlmService;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsService;
import money.hejje.news.NewsSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** News on Jev (plan M9.3): the jev classifier, shadow mode, fallback on failure, and index risk events from headlines. */
class NewsClassifierJevIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 12, 3);
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired NewsService news;
    @Autowired NewsClassification classification;
    @Autowired NewsRiskEvents riskEvents;
    @Autowired EventService events;
    @Autowired CalibrationService calibration;
    @Autowired JevTransport transport;
    @Autowired LlmService llm;
    @Autowired InstrumentService instruments;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    FixtureJev jev;
    FixtureLlmProvider fixtureLlm;
    UUID infy;
    UUID sourceId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE news_bias, news_assessment, news_item, news_source, llm_call CASCADE");
        jdbc.update("DELETE FROM market_event WHERE source = 'news-jev'");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        jev = (FixtureJev) transport;
        jev.reset();
        fixtureLlm = (FixtureLlmProvider) llm.provider("fixture").orElseThrow();
        fixtureLlm.reset();
        news.seedSource("Jev wire", "https://jev.invalid/rss", NewsSource.Kind.RSS, 0.8, true);
        sourceId = news.sources().get(0).id();
        clock.setIst(DAY + "T10:00:00");
        jdbc.update("UPDATE news_source SET last_polled_at = ?, last_error = NULL", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
        // a relevant, bullish, notable, new contract story
        jev.noul("relevance", 0.9);
        jev.script("direction", (q, s) -> JSON.createObjectNode().put("type", "choice").put("choice", "bullish").put("confidence", 0.8)
                .set("probabilities", JSON.createObjectNode().put("bullish", 0.8).put("bearish", 0.1).put("neutral", 0.1)));
        jev.script("materiality", (q, s) -> JSON.createObjectNode().put("type", "score").put("score", 1.0).put("confidence", 0.7));
        jev.script("novelty", (q, s) -> JSON.createObjectNode().put("type", "score").put("score", 2.0).put("confidence", 0.7));
        jev.script("event_type", (q, s) -> JSON.createObjectNode().put("type", "choice").put("choice", "CONTRACT").put("confidence", 0.9)
                .set("probabilities", JSON.createObjectNode().put("CONTRACT", 0.9)));
    }

    @AfterEach
    void tearDown() {
        classification.mode(NewsClassification.Mode.LLM);
        jev.reset();
        fixtureLlm.reset();
        clock.set(Instant.now());
    }

    FeedParser.Fetched story(String url, String title) {
        return new FeedParser.Fetched(url, title, "Infosys signed a multi-year contract.", clock.instant().minusSeconds(300));
    }

    @Test
    void theJevClassifierFeedsTheUnchangedBiasWithoutTheLlm() {
        classification.mode(NewsClassification.Mode.JEV);
        NewsService.IngestResult r = news.ingest(sourceId, List.of(story("https://j/1", "Infosys wins $1.5 billion deal")));
        assertThat(r.assessed()).isEqualTo(1);
        assertThat(fixtureLlm.requests()).isEmpty(); // the LLM was not asked
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM news_assessment");
        assertThat(row).containsEntry("model", "jev:fixture-jev").containsEntry("prompt_version", "jev-news-v1").containsEntry("shadow", false);
        assertThat((Double) row.get("direction")).isEqualTo(0.7, org.assertj.core.api.Assertions.within(1e-9));
        assertThat((Double) row.get("materiality")).isEqualTo(0.5);
        NewsBias bias = news.bias(infy);
        assertThat(bias.available()).isTrue();
        assertThat(bias.items()).isEqualTo(1);
        assertThat(bias.score()).isPositive();
        // the direction answer is recorded for calibration (P(up | a directional move) = 0.8 / 0.9)
        assertThat(calibration.report("news.direction", "1", DAY, DAY).pending()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void withJevFailingStoriesStayUnassessedAndTheBiasIsNeutral() {
        classification.mode(NewsClassification.Mode.JEV);
        jev.failWith(new IllegalStateException("upstream down"));
        NewsService.IngestResult r = news.ingest(sourceId, List.of(story("https://j/2", "Infosys wins another deal")));
        assertThat(r.stored()).isEqualTo(1);
        assertThat(r.assessed()).isZero();
        NewsBias bias = news.bias(infy);
        assertThat(bias.items()).isZero();
        assertThat(bias.label().name()).isEqualTo("NEUTRAL");
        assertThat(riskEvents.check()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shadowModeUsesTheLlmAndStoresJevBesideItForTheComparison() {
        classification.mode(NewsClassification.Mode.SHADOW);
        fixtureLlm.respondWhenContains("Title: Infosys wins", """
                {"assessments":[{"symbol":"NSE:INFY","relevance":0.9,"direction":0.5,"materiality":0.6,"novelty":1.0,"confidence":0.9,"event_type":"CONTRACT",
                 "summary":"Infosys won a large deal."}]}
                """);
        NewsService.IngestResult r = news.ingest(sourceId, List.of(story("https://j/3", "Infosys wins $2 billion deal")));
        assertThat(r.assessed()).isEqualTo(1); // only the used (LLM) assessment counts
        assertThat(jdbc.queryForObject("SELECT count(*) FROM news_assessment WHERE shadow", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM news_assessment WHERE NOT shadow", Integer.class)).isEqualTo(1);
        NewsBias bias = news.bias(infy);
        assertThat(bias.items()).isEqualTo(1);
        assertThat(bias.evidence().get(0)).contains("Infosys won a large deal."); // the LLM's summary, not Jev's title

        ResponseEntity<Map> cmp = rest.exchange("/api/v1/news/classifier-comparison?from=" + DAY + "&to=" + DAY, HttpMethod.GET,
                new HttpEntity<>(bearer(adminAccessToken())), Map.class);
        assertThat(cmp.getBody()).containsEntry("classifier", "shadow").containsEntry("pairs", 1).containsEntry("directionAgreement", 1.0);
        assertThat(((Number) cmp.getBody().get("materialityMae")).doubleValue()).isEqualTo(0.1, org.assertj.core.api.Assertions.within(1e-9));
        assertThat((Map<String, Object>) cmp.getBody().get("directionCalibration")).containsEntry("purpose", "news.direction");
    }

    @Test
    void theSameRiskEventSeenInThreePollsIsRecordedOnce() {
        news.ingest(sourceId, List.of(new FeedParser.Fetched("https://j/r1", "RBI policy decision today: repo rate expected unchanged", "", clock.instant()),
                new FeedParser.Fetched("https://j/r2", "Markets cautious ahead of RBI policy outcome", "", clock.instant())));
        jev.noul("risk_event_today", 0.85);
        jev.script("event_kind", (q, s) -> JSON.createObjectNode().put("type", "choice").put("choice", "RBI").put("confidence", 0.9)
                .set("probabilities", JSON.createObjectNode().put("RBI", 0.9).put("OTHER", 0.1)));
        for (int i = 0; i < 3; i++) {
            assertThat(riskEvents.check()).contains(EventType.RBI_POLICY);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM market_event WHERE source = 'news-jev'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("type", "RBI_POLICY").containsEntry("scope", "MARKET").containsEntry("all_day", true);
        Optional<MarketEvent> seen = events.marketEvents(DAY).stream().filter(e -> "news-jev".equals(e.source())).findFirst();
        assertThat(seen).isPresent();
        // below the threshold nothing is recorded
        jdbc.update("DELETE FROM market_event WHERE source = 'news-jev'");
        jev.noul("risk_event_today", 0.4);
        assertThat(riskEvents.check()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_event WHERE source = 'news-jev'", Integer.class)).isZero();
    }
}
