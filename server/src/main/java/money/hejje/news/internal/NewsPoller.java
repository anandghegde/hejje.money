package money.hejje.news.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import money.hejje.news.NewsProperties;
import money.hejje.news.NewsService;
import money.hejje.news.NewsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Seeds {@code news_source} from the configured YAML at boot and polls every {@code hejje.news.poll-minutes}. */
@Component
class NewsPoller {

    private static final Logger log = LoggerFactory.getLogger(NewsPoller.class);

    private final NewsService news;
    private final NewsProperties props;
    private final ResourceLoader resources;

    NewsPoller(NewsService news, NewsProperties props, ResourceLoader resources) {
        this.news = news;
        this.props = props;
        this.resources = resources;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        try {
            Resource resource = resources.getResource(props.sources());
            if (!resource.exists()) {
                log.warn("News source file {} not found", props.sources());
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                int n = 0;
                for (JsonNode s : new ObjectMapper(new YAMLFactory()).readTree(in).path("sources")) {
                    news.seedSource(s.path("name").asText(), s.path("url").asText(), NewsSource.Kind.valueOf(s.path("kind").asText("RSS").toUpperCase()),
                            s.path("reliability").asDouble(0.7), s.path("enabled").asBoolean(true));
                    n++;
                }
                log.info("News sources seeded: {} ({})", n, props.enabled() ? "polling every " + props.pollMinutes() + " min" : "news disabled");
            }
        } catch (Exception e) {
            log.warn("News source seeding failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${hejje.news.poll-minutes:5}m", initialDelayString = "PT30S")
    void poll() {
        if (!props.enabled()) {
            return;
        }
        try {
            NewsService.PollResult r = news.poll();
            log.info("News poll: {} sources, {} fetched, {} new, {} assessments{}", r.sources(), r.fetched(), r.stored(), r.assessed(),
                    r.errors().isEmpty() ? "" : ", errors " + r.errors());
        } catch (RuntimeException e) {
            log.warn("News poll failed: {}", e.getMessage());
        }
    }
}
