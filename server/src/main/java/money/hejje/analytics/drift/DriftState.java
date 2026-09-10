package money.hejje.analytics.drift;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The stored drift state of one deployment.
 *
 * @param actedStatus    the worst status whose actions have been taken; lowered when the status improves, so a status
 *                       takes its actions once per escalation
 * @param overrideStatus while set, statuses no worse than this take no actions (manual override with a reason)
 */
public record DriftState(UUID deploymentId, UUID versionId, UUID strategyId, DriftStatus status, DriftStatus actedStatus, List<String> triggered,
        DriftStatus overrideStatus, String overrideReason, String overrideBy, Instant overrideAt, Instant updatedAt) {

    public DriftState {
        triggered = triggered == null ? List.of() : List.copyOf(triggered);
    }

    /** True when an override covers {@code s} (actions for it are suppressed). */
    public boolean overrides(DriftStatus s) {
        return overrideStatus != null && !s.worseThan(overrideStatus);
    }
}
