package money.hejje.news;

import java.time.Instant;
import java.util.UUID;

/**
 * The LLM's structured reading of one item for one instrument (or sector), retained as evidence (PRD 17.2).
 *
 * @param relevance   0..1 how much the item is about the instrument
 * @param direction   −1..+1 (bearish..bullish) for the instrument
 * @param materiality 0..1 expected impact
 * @param novelty     0..1 (0 = already known / discounted)
 * @param confidence  0..1 the model's confidence in its reading
 */
public record NewsAssessment(UUID id, UUID itemId, UUID instrumentId, String sector, double relevance, double direction, double materiality, double novelty,
        double confidence, String eventType, String summary, String model, String promptVersion, Instant createdAt) {
}
