package money.hejje.news.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Ids;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.llm.LlmException;
import money.hejje.llm.LlmService;
import money.hejje.llm.Prompts;
import money.hejje.llm.StructuredOutput;
import money.hejje.news.NewsAssessment;
import money.hejje.news.NewsItem;
import money.hejje.news.NewsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** LLM classification (profile {@code news}, prompt {@code news_classify_v1}) of one item against its matched candidates. */
@Component
public class NewsClassifier {

    private static final Logger log = LoggerFactory.getLogger(NewsClassifier.class);
    public static final String PROMPT_VERSION = "news_classify_v1";
    static final String SYSTEM = "You are a careful financial news classifier. Answer with JSON only.";
    static final List<String> EVENT_TYPES = List.of("RESULTS", "GUIDANCE", "CONTRACT", "MANAGEMENT", "REGULATORY", "LEGAL", "MACRO", "SECTOR", "RATING",
            "CORPORATE_ACTION", "DEAL", "OTHER");

    private final StructuredOutput structured;
    private final LlmService llm;
    private final InstrumentService instruments;
    private final ObjectMapper json;
    private final String template = Prompts.load(PROMPT_VERSION + ".txt");
    private final JsonNode schema;

    NewsClassifier(StructuredOutput structured, LlmService llm, InstrumentService instruments, ObjectMapper json) {
        this.structured = structured;
        this.llm = llm;
        this.instruments = instruments;
        this.json = json;
        this.schema = schema(json);
    }

    public boolean available() {
        return llm.enabled();
    }

    /** Assessments for the item (empty when the LLM is off, no candidates, or the call failed). */
    public List<NewsAssessment> classify(NewsItem item, NewsSource source, List<InstrumentMatcher.Candidate> candidates, Instant now) {
        if (!llm.enabled() || candidates.isEmpty()) {
            return List.of();
        }
        StringBuilder c = new StringBuilder();
        for (InstrumentMatcher.Candidate cand : candidates) {
            c.append("- ").append(cand.symbol()).append(": matched \"").append(cand.matchedOn()).append("\"")
                    .append(cand.sector() == null ? "" : ", sector " + cand.sector()).append('\n');
        }
        String prompt = Prompts.fill(template, Map.of("title", item.title(), "summary", item.summary() == null ? "" : item.summary(),
                "published", item.publishedAt().toString(), "source", source == null ? "unknown" : source.name(), "candidates", c.toString()));
        JsonNode answer;
        try {
            answer = structured.ask("news", "news_classify", PROMPT_VERSION, SYSTEM, prompt, schema);
        } catch (LlmException e) {
            log.warn("News classification failed for {}: {}", item.id(), e.getMessage());
            return List.of();
        }
        List<NewsAssessment> out = new ArrayList<>();
        for (JsonNode a : answer.path("assessments")) {
            String symbol = a.path("symbol").asText("").trim().toUpperCase(Locale.ROOT);
            Optional<Instrument> instrument = candidates.stream().anyMatch(x -> x.symbol().equals(symbol)) ? instruments.resolve(symbol) : Optional.empty();
            if (instrument.isEmpty()) {
                continue; // the model may only answer about the candidates it was given
            }
            String sector = candidates.stream().filter(x -> x.symbol().equals(symbol)).map(InstrumentMatcher.Candidate::sector).findFirst().orElse(null);
            String eventType = a.path("event_type").asText("OTHER").toUpperCase(Locale.ROOT);
            out.add(new NewsAssessment(Ids.newId(), item.id(), instrument.get().id(), sector, clip01(a.path("relevance").asDouble()),
                    Math.max(-1, Math.min(1, a.path("direction").asDouble())), clip01(a.path("materiality").asDouble()), clip01(a.path("novelty").asDouble(1.0)),
                    clip01(a.path("confidence").asDouble()), EVENT_TYPES.contains(eventType) ? eventType : "OTHER", a.path("summary").asText(null),
                    "news-profile", PROMPT_VERSION, now));
        }
        return out;
    }

    private static double clip01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    static JsonNode schema(ObjectMapper json) {
        try {
            return json.readTree("""
                    {"type":"object","required":["assessments"],"properties":{"assessments":{"type":"array","items":{"type":"object",
                     "required":["symbol","relevance","direction","materiality","confidence"],
                     "properties":{"symbol":{"type":"string"},"relevance":{"type":"number","minimum":0,"maximum":1},
                                   "direction":{"type":"number","minimum":-1,"maximum":1},"materiality":{"type":"number","minimum":0,"maximum":1},
                                   "novelty":{"type":"number","minimum":0,"maximum":1},"confidence":{"type":"number","minimum":0,"maximum":1},
                                   "event_type":{"type":"string"},"summary":{"type":"string"}}}}}}
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
