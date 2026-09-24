package money.hejje.news.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.news.NewsAssessment;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsItem;
import money.hejje.news.NewsService;
import money.hejje.news.NewsSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class NewsController {

    private final NewsService news;

    NewsController(NewsService news) {
        this.news = news;
    }

    @GetMapping("/news")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<Map<String, Object>> items(@RequestParam(required = false) UUID instrumentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from, @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (NewsItem item : news.items(instrumentId, from, Math.min(limit, 200))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.id());
            m.put("sourceId", item.sourceId());
            m.put("url", item.url());
            m.put("title", item.title());
            m.put("summary", item.summary());
            m.put("publishedAt", item.publishedAt());
            m.put("assessments", news.assessmentsOf(item.id()).stream().map(NewsController::assessment).toList());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/context/news-bias")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    NewsBias bias(@RequestParam UUID instrumentId) {
        return news.bias(instrumentId);
    }

    /** Plan M9.3: Jev against the LLM on the same stories (shadow mode), with news-direction calibration. Default: the last 30 days. */
    @GetMapping("/news/classifier-comparison")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    NewsService.ClassifierComparison comparison(@RequestParam(required = false) java.time.LocalDate from, @RequestParam(required = false) java.time.LocalDate to) {
        return news.classifierComparison(from, to);
    }

    @GetMapping("/news/sources")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<NewsSource> sources() {
        return news.sources();
    }

    record SourceUpdate(Boolean enabled, Double reliability) {}

    @PutMapping("/news/sources/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    NewsSource update(@PathVariable UUID id, @RequestBody SourceUpdate body) {
        return news.updateSource(id, body.enabled(), body.reliability());
    }

    @PostMapping("/news/poll")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    NewsService.PollResult poll() {
        return news.poll();
    }

    private static Map<String, Object> assessment(NewsAssessment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instrumentId", a.instrumentId());
        m.put("sector", a.sector());
        m.put("relevance", a.relevance());
        m.put("direction", a.direction());
        m.put("materiality", a.materiality());
        m.put("novelty", a.novelty());
        m.put("confidence", a.confidence());
        m.put("eventType", a.eventType());
        m.put("summary", a.summary());
        m.put("promptVersion", a.promptVersion());
        return m;
    }
}
