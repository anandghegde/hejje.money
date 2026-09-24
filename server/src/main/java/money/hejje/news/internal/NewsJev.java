package money.hejje.news.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import money.hejje.calibration.CalibrationService;
import money.hejje.calibration.LabelRule;
import money.hejje.calibration.Prediction;
import money.hejje.common.Ids;
import money.hejje.common.Side;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.llm.JevAnswer;
import money.hejje.llm.JevQuestionSet;
import money.hejje.llm.JevQuestionSets;
import money.hejje.llm.JevResult;
import money.hejje.llm.JevService;
import money.hejje.news.NewsAssessment;
import money.hejje.news.NewsItem;
import money.hejje.news.NewsProperties;
import money.hejje.news.NewsSource;
import org.springframework.stereotype.Component;

/**
 * News classification with Jev (plan M9.3, question set {@code config/jev/news.yaml}): one call per (story, candidate),
 * mapped into the existing {@link NewsAssessment} fields so the bias formula is unchanged. A failed call leaves the
 * candidate unassessed. Each direction answer is recorded for calibration as {@code news.direction}.
 */
@Component
public class NewsJev {

    public static final String PURPOSE = "news";
    public static final String PROMPT_VERSION = "jev-news-v1";
    public static final String CALIBRATION_PURPOSE = "news.direction";

    private final JevService jev;
    private final JevQuestionSets sets;
    private final InstrumentMatcher matcher;
    private final InstrumentService instruments;
    private final CalibrationService calibration;
    private final NewsProperties props;
    private final ObjectMapper json;

    NewsJev(JevService jev, JevQuestionSets sets, InstrumentMatcher matcher, InstrumentService instruments, CalibrationService calibration,
            NewsProperties props, ObjectMapper json) {
        this.jev = jev;
        this.sets = sets;
        this.matcher = matcher;
        this.instruments = instruments;
        this.calibration = calibration;
        this.props = props;
        this.json = json;
    }

    public boolean available() {
        return jev.enabled();
    }

    public List<NewsAssessment> classify(NewsItem item, NewsSource source, List<InstrumentMatcher.Candidate> candidates, Instant now) {
        if (!jev.enabled() || candidates.isEmpty()) {
            return List.of();
        }
        JevQuestionSet set = sets.get("news");
        List<NewsAssessment> out = new ArrayList<>();
        for (InstrumentMatcher.Candidate c : candidates) {
            Optional<Instrument> instrument = instruments.resolve(c.symbol());
            if (instrument.isEmpty()) {
                continue;
            }
            ObjectNode state = json.createObjectNode();
            state.put("symbol", c.symbol());
            state.set("aliases", json.valueToTree(matcher.aliases().stream().filter(a -> a.symbol().equals(c.symbol())).findFirst()
                    .map(InstrumentMatcher.Alias::names).orElse(List.of())));
            state.put("title", item.title());
            state.put("summary", item.summary() == null ? "" : item.summary());
            state.put("source", source == null ? "unknown" : source.name());
            state.put("publishedAt", item.publishedAt().toString());
            JevResult r = jev.evaluate(PURPOSE, c.symbol(), state, set);
            if (!r.ok()) {
                continue; // unassessed, as when the LLM fails
            }
            NewsAssessment a = map(r, item, instrument.get(), c.sector(), now);
            out.add(a);
            recordDirection(r, set, a, instrument.get(), now);
        }
        return out;
    }

    /**
     * The mapping of docs/news.md: relevance = the noul; direction = P(bullish) − P(bearish); materiality and novelty =
     * the expected level / 2; confidence = the direction answer's confidence (else the chosen option's probability);
     * summary = the title (Jev returns no text).
     */
    static NewsAssessment map(JevResult r, NewsItem item, Instrument instrument, String sector, Instant now) {
        JevAnswer direction = r.answer("direction").orElse(null);
        double bull = direction == null ? 0 : direction.probabilityOf("bullish");
        double bear = direction == null ? 0 : direction.probabilityOf("bearish");
        Double confidence = direction == null ? null : direction.confidence() != null ? direction.confidence() : direction.probability();
        String eventType = r.answer("event_type").map(JevAnswer::choice).map(s -> s.toUpperCase(Locale.ROOT)).orElse("OTHER");
        return new NewsAssessment(Ids.newId(), item.id(), instrument.id(), sector, clip01(r.noul("relevance", 0)), Math.max(-1, Math.min(1, bull - bear)),
                clip01(level(r, "materiality") / 2), clip01(level(r, "novelty") / 2), clip01(confidence == null ? 0 : confidence),
                NewsClassifier.EVENT_TYPES.contains(eventType) ? eventType : "OTHER", item.title(), "jev:" + r.model(), PROMPT_VERSION, now);
    }

    /** P(up | a directional move) = P(bullish) / (P(bullish) + P(bearish)), for relevant stories only (docs/calibration.md). */
    private void recordDirection(JevResult r, JevQuestionSet set, NewsAssessment a, Instrument instrument, Instant now) {
        JevAnswer direction = r.answer("direction").orElse(null);
        if (direction == null || r.callId() == null || a.relevance() < props.minRelevance()) {
            return;
        }
        double bull = direction.probabilityOf("bullish");
        double bear = direction.probabilityOf("bearish");
        if (bull + bear <= 0) {
            return;
        }
        calibration.record(new Prediction("jev", r.callId().toString(), "direction", CALIBRATION_PURPOSE, set.version(), clip01(bull / (bull + bear)),
                instrument.id(), LabelRule.DIRECTION_NEXT_CLOSE, Side.BUY, null, now));
    }

    private static double level(JevResult r, String key) {
        return r.answer(key).map(JevAnswer::score).orElse(0.0);
    }

    private static double clip01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
