package money.hejje.notify;

import java.time.Instant;
import java.util.UUID;

/** A notification goes to {@code channel} when an enabled rule for its type exists and its severity is at least {@code minSeverity}. */
public record NotificationRule(UUID id, NotificationType eventType, NotificationType.Channel channel, NotificationType.Severity minSeverity, boolean enabled,
        Instant updatedAt, String updatedBy) {
}
