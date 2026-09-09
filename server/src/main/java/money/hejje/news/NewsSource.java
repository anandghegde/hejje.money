package money.hejje.news;

import java.time.Instant;
import java.util.UUID;

/** A feed. {@code reliability} (0..1) weights every item from it in the bias. */
public record NewsSource(UUID id, String name, String url, Kind kind, double reliability, boolean enabled, Instant lastPolledAt, String lastError) {

    public enum Kind { RSS, ATOM, JSON }
}
