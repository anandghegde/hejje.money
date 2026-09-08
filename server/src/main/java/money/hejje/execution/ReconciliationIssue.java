package money.hejje.execution;

import java.time.Instant;
import java.util.UUID;

/** A difference detected between Hejje state and broker truth (PRD section 38). */
public record ReconciliationIssue(UUID id, String kind, ReconciliationSeverity severity, UUID instrumentId, UUID orderId,
        String expected, String observed, String detail, Instant detectedAt, Instant resolvedAt) {

    public boolean isOpen() {
        return resolvedAt == null;
    }
}
