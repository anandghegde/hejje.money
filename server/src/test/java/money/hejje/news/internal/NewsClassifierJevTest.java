package money.hejje.news.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import money.hejje.events.EventType;
import money.hejje.instruments.Instrument;
import money.hejje.llm.JevAnswer;
import money.hejje.llm.JevResult;
import money.hejje.news.NewsAssessment;
import money.hejje.news.NewsItem;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** The Jev → news_assessment mapping of docs/news.md (plan M9.3). */
class NewsClassifierJevTest {

    static JevResult result(Map<String, JevAnswer> answers) {
        return new JevResult(true, JevResult.Outcome.OK, answers, 120, 400, "jev-1.13.0", UUID.randomUUID(), null);
    }

    @Test
    void mapsTheAnswersIntoTheExistingFields() {
        JevResult r = result(Map.of(
                "relevance", new JevAnswer("relevance", "noul", null, null, 0.92, Map.of(), null),
                "direction", new JevAnswer("direction", "choice", "bullish", null, null, Map.of("bullish", 0.7, "bearish", 0.1, "neutral", 0.2), 0.8),
                "materiality", new JevAnswer("materiality", "score", null, 1.5, null, Map.of(), 0.6),
                "novelty", new JevAnswer("novelty", "score", null, 2.0, null, Map.of(), 0.9),
                "event_type", new JevAnswer("event_type", "choice", "CONTRACT", null, null, Map.of("CONTRACT", 0.9), 0.9)));
        Instrument infy = Mockito.mock(Instrument.class);
        UUID id = UUID.randomUUID();
        Mockito.when(infy.id()).thenReturn(id);
        NewsItem item = new NewsItem(UUID.randomUUID(), UUID.randomUUID(), "https://t/1", "Infosys wins deal", "…", null, Instant.EPOCH, Instant.EPOCH, "h", "n");
        NewsAssessment a = NewsJev.map(r, item, infy, "IT", Instant.EPOCH);
        assertThat(a.instrumentId()).isEqualTo(id);
        assertThat(a.relevance()).isEqualTo(0.92);
        assertThat(a.direction()).isCloseTo(0.6, within(1e-9));    // P(bullish) − P(bearish)
        assertThat(a.materiality()).isCloseTo(0.75, within(1e-9)); // expected level 1.5 of 0..2, / 2
        assertThat(a.novelty()).isEqualTo(1.0);
        assertThat(a.confidence()).isEqualTo(0.8);                 // the direction answer's confidence
        assertThat(a.eventType()).isEqualTo("CONTRACT");
        assertThat(a.summary()).isEqualTo("Infosys wins deal");      // Jev returns no text
        assertThat(a.model()).isEqualTo("jev:jev-1.13.0");
        assertThat(a.promptVersion()).isEqualTo("jev-news-v1");
    }

    @Test
    void missingAnswersFallBackToNeutralValues() {
        JevResult r = result(Map.of("direction", new JevAnswer("direction", "choice", "bearish", null, null, Map.of("bearish", 0.55, "neutral", 0.45), null),
                "event_type", new JevAnswer("event_type", "choice", "nonsense", null, null, Map.of(), null)));
        Instrument i = Mockito.mock(Instrument.class);
        NewsItem item = new NewsItem(UUID.randomUUID(), UUID.randomUUID(), "u", "t", null, null, Instant.EPOCH, Instant.EPOCH, "h", "n");
        NewsAssessment a = NewsJev.map(r, item, i, null, Instant.EPOCH);
        assertThat(a.relevance()).isZero();
        assertThat(a.direction()).isCloseTo(-0.55, within(1e-9));
        assertThat(a.confidence()).isEqualTo(0.55); // no confidence: the chosen option's probability
        assertThat(a.eventType()).isEqualTo("OTHER");
    }

    @Test
    void riskEventKindsMapToMarketMacroTypes() {
        assertThat(NewsRiskEvents.type("RBI")).isEqualTo(EventType.RBI_POLICY);
        assertThat(NewsRiskEvents.type("FED")).isEqualTo(EventType.FED_DECISION);
        assertThat(NewsRiskEvents.type("BUDGET")).isEqualTo(EventType.BUDGET);
        assertThat(NewsRiskEvents.type("CPI")).isEqualTo(EventType.INDIA_CPI);
        assertThat(NewsRiskEvents.type("OTHER")).isEqualTo(EventType.GEOPOLITICAL);
        assertThat(EventType.values()).filteredOn(t -> t == NewsRiskEvents.type("OTHER")).allMatch(t -> t.isMacro() && t.isMarketScope());
    }
}
