package money.hejje.ratings;

import java.time.Instant;
import java.util.UUID;

public record WatchlistItem(String symbol, UUID instrumentId, String note, Instant addedAt) {
}
