package money.hejje.news.internal;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import money.hejje.news.NewsAssessment;
import money.hejje.news.NewsItem;
import money.hejje.news.NewsProperties;
import money.hejje.news.NewsSource;
import org.springframework.stereotype.Component;

/**
 * Which classifier assesses news ({@code hejje.news.classifier}, plan M9.3): {@code llm} (Phase 3), {@code jev}, or
 * {@code shadow} (the LLM's result is used; Jev's is stored beside it with {@code shadow=true} for the comparison).
 */
@Component
public class NewsClassification {

    public enum Mode { LLM, JEV, SHADOW }

    /** {@code primary} feeds the bias; {@code shadow} is stored for comparison only. */
    public record Result(List<NewsAssessment> primary, List<NewsAssessment> shadow) {}

    private final NewsClassifier llm;
    private final NewsJev jev;
    private volatile Mode mode;

    NewsClassification(NewsClassifier llm, NewsJev jev, NewsProperties props) {
        this.llm = llm;
        this.jev = jev;
        this.mode = Mode.valueOf(props.classifier().trim().toUpperCase(Locale.ROOT));
    }

    public Mode mode() {
        return mode;
    }

    /** For tests that share one context. */
    public void mode(Mode mode) {
        this.mode = mode;
    }

    public boolean available() {
        return mode == Mode.JEV ? jev.available() : llm.available();
    }

    public String unavailableReason() {
        return mode == Mode.JEV ? "Jev disabled" : "LLM disabled";
    }

    public Result classify(NewsItem item, NewsSource source, List<InstrumentMatcher.Candidate> candidates, Instant now) {
        return switch (mode) {
            case LLM -> new Result(llm.classify(item, source, candidates, now), List.of());
            case JEV -> new Result(jev.classify(item, source, candidates, now), List.of());
            case SHADOW -> new Result(llm.classify(item, source, candidates, now), jev.classify(item, source, candidates, now));
        };
    }
}
