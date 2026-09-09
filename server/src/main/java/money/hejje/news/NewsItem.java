package money.hejje.news;

import java.time.Instant;
import java.util.UUID;

/** One deduplicated story. */
public record NewsItem(UUID id, UUID sourceId, String url, String title, String summary, String body, Instant publishedAt, Instant fetchedAt, String hash,
        String normTitle) {
}
