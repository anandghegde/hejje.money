package money.hejje.news.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.news.NewsAssessment;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsBiasLabel;
import money.hejje.news.NewsItem;
import money.hejje.news.NewsProperties;
import money.hejje.news.NewsSource;

/**
 * PRD 17.2 deterministic aggregation (docs/news.md): per assessed item
 * {@code direction × materiality × confidence × source reliability × recency decay (half-life 4 h)}, summed, times a
 * confirmation factor for distinct sources ({@code 1 + 0.25 × (n − 1)}, at most 1.5), clipped to −1..+1. Pure.
 */
public final class NewsBiasAggregator {

    private NewsBiasAggregator() {
    }

    /** @param reaction the price/volume reaction line (may be null) with its agreement sign: +1 agrees, −1 disagrees, 0 unknown */
    public record Reaction(String line, int agreement) {}

    public static NewsBias aggregate(UUID instrumentId, Instant now, List<NewsAssessment> assessments, Map<UUID, NewsItem> items, Map<UUID, NewsSource> sources,
            NewsProperties p, Reaction reaction) {
        double sum = 0;
        Set<UUID> distinctSources = new HashSet<>();
        List<NewsBias.Contribution> contributions = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (NewsAssessment a : assessments) {
            NewsItem item = items.get(a.itemId());
            if (item == null || a.relevance() < p.minRelevance()) {
                continue;
            }
            NewsSource source = sources.get(item.sourceId());
            double reliability = source == null ? 0.5 : source.reliability();
            double ageHours = Math.max(0, Duration.between(item.publishedAt(), now).toMinutes()) / 60.0;
            double decay = Math.pow(0.5, ageHours / (p.halfLife().toMinutes() / 60.0));
            double weight = a.materiality() * a.confidence() * reliability * decay * a.novelty();
            double contribution = a.direction() * weight;
            sum += contribution;
            distinctSources.add(item.sourceId());
            contributions.add(new NewsBias.Contribution(item.id(), item.title(), item.url(), source == null ? "?" : source.name(), item.publishedAt(), a.direction(),
                    a.materiality(), a.confidence(), round(contribution), a.summary()));
        }
        if (contributions.isEmpty()) {
            evidence.add("No material news in the last " + p.window().toHours() + " h");
            return new NewsBias(instrumentId, now, 0, NewsBiasLabel.NEUTRAL, 0, evidence, true, List.of());
        }
        double confirmation = Math.min(1.5, 1 + 0.25 * (distinctSources.size() - 1));
        double score = Math.max(-1, Math.min(1, sum * confirmation));
        NewsBiasLabel label = score >= p.strongScore() ? NewsBiasLabel.STRONGLY_BULLISH : score >= p.mildScore() ? NewsBiasLabel.BULLISH
                : score <= -p.strongScore() ? NewsBiasLabel.STRONGLY_BEARISH : score <= -p.mildScore() ? NewsBiasLabel.BEARISH : NewsBiasLabel.NEUTRAL;
        contributions.sort((x, y) -> Double.compare(Math.abs(y.weight()), Math.abs(x.weight())));
        for (NewsBias.Contribution c : contributions) {
            double ageHours = Math.max(0, Duration.between(c.publishedAt(), now).toMinutes()) / 60.0;
            evidence.add(String.format(Locale.ROOT, "%s %s (%s, %.1f h ago, materiality %.1f, %+.2f)", c.direction() > 0.1 ? "▲" : c.direction() < -0.1 ? "▼" : "•",
                    c.summary() == null || c.summary().isBlank() ? c.title() : c.summary(), c.source(), ageHours, c.materiality(), c.weight()));
        }
        if (distinctSources.size() > 1) {
            evidence.add(String.format(Locale.ROOT, "Confirmed by %d sources (×%.2f)", distinctSources.size(), confirmation));
        }
        if (reaction != null && reaction.line() != null) {
            evidence.add(reaction.line());
        }
        return new NewsBias(instrumentId, now, round(score), label, contributions.size(), evidence, true, contributions);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
