package money.hejje.news.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.common.time.HejjeClock;
import money.hejje.events.EventService;
import money.hejje.events.EventType;
import money.hejje.llm.JevAnswer;
import money.hejje.llm.JevQuestionSet;
import money.hejje.llm.JevQuestionSets;
import money.hejje.llm.JevResult;
import money.hejje.llm.JevService;
import money.hejje.news.NewsItem;
import money.hejje.news.NewsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Index risk events from headlines (plan M9.3, {@code config/jev/news-risk.yaml}): after a poll that stored new items,
 * one Jev call over the day's newest {@code market-headline-count} titles. At P(risk event today) ≥
 * {@code risk-event-threshold} a market-wide, all-day macro event of source {@code news-jev} is upserted, one per date and
 * kind, and the event-risk rules treat it like any calendar event.
 */
@Component
public class NewsRiskEvents {

    private static final Logger log = LoggerFactory.getLogger(NewsRiskEvents.class);
    public static final String SOURCE = "news-jev";

    private final JevService jev;
    private final JevQuestionSets sets;
    private final NewsStore store;
    private final EventService events;
    private final NewsProperties props;
    private final HejjeClock clock;
    private final ObjectMapper json;

    NewsRiskEvents(JevService jev, JevQuestionSets sets, NewsStore store, EventService events, NewsProperties props, HejjeClock clock, ObjectMapper json) {
        this.jev = jev;
        this.sets = sets;
        this.store = store;
        this.events = events;
        this.props = props;
        this.clock = clock;
        this.json = json;
    }

    /** The detected event, or empty (Jev off or failed, no headlines, or below the threshold). Never throws. */
    public Optional<EventType> check() {
        if (!jev.enabled()) {
            return Optional.empty();
        }
        try {
            LocalDate today = clock.today();
            List<NewsItem> items = store.items(today.atStartOfDay(clock.zone()).toInstant(), null, Math.max(1, props.marketHeadlineCount()));
            if (items.isEmpty()) {
                return Optional.empty();
            }
            ObjectNode state = json.createObjectNode();
            state.put("date", today.toString());
            items.forEach(i -> state.withArray("headlines").add(i.title()));
            JevQuestionSet set = sets.get("news-risk");
            JevResult r = jev.evaluate("news-risk", today.toString(), state, set);
            double p = r.noul("risk_event_today", 0);
            if (!r.ok() || p < props.riskEventThreshold()) {
                return Optional.empty();
            }
            String kind = r.answer("event_kind").map(JevAnswer::choice).orElse("OTHER");
            EventType type = type(kind);
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("probability", p);
            raw.put("kind", kind);
            raw.put("jevCallId", String.valueOf(r.callId()));
            raw.put("setVersion", set.version());
            raw.put("headlines", items.stream().map(NewsItem::title).toList());
            events.addDetected(type, today, "Index risk event in the headlines: " + kind, SOURCE, p, raw);
            return Optional.of(type);
        } catch (RuntimeException e) {
            log.warn("Index risk-event check failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** RBI, FED, BUDGET, CPI map to their calendar types; OTHER to GEOPOLITICAL (a market-wide macro type). */
    static EventType type(String kind) {
        return switch (kind) {
            case "RBI" -> EventType.RBI_POLICY;
            case "FED" -> EventType.FED_DECISION;
            case "BUDGET" -> EventType.BUDGET;
            case "CPI" -> EventType.INDIA_CPI;
            default -> EventType.GEOPOLITICAL;
        };
    }
}
