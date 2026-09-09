package money.hejje.news;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The aggregate for one instrument over the trailing window.
 *
 * @param score     −1..+1
 * @param items     assessed items that contributed
 * @param evidence  templated lines: contributing stories, the price/volume reaction check
 * @param available false when news or the LLM is disabled or the feed is stale (score 0, NEUTRAL, reason in evidence)
 * @param sources   the contributing items for the UI (title, url, source, published, direction, materiality)
 */
public record NewsBias(UUID instrumentId, Instant computedAt, double score, NewsBiasLabel label, int items, List<String> evidence, boolean available,
        List<Contribution> sources) {

    public NewsBias {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public record Contribution(UUID itemId, String title, String url, String source, Instant publishedAt, double direction, double materiality,
            double confidence, double weight, String summary) {
    }

    public static NewsBias unavailable(UUID instrumentId, Instant now, String reason) {
        return new NewsBias(instrumentId, now, 0, NewsBiasLabel.NEUTRAL, 0, List.of(reason), false, List.of());
    }
}
