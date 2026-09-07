package money.hejje.audit;

import java.time.Instant;
import java.util.UUID;

/** Filter for {@link AuditService#query(AuditQuery)}. Null fields are ignored. Results are newest first. */
public record AuditQuery(Instant from, Instant to, AuditEventType type, UUID orderId, int page, int size) {

    public static final int MAX_SIZE = 500;

    public AuditQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
    }
}
