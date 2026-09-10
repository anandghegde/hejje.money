package money.hejje.notify;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One notification in the in-app inbox (plan M5.5). {@code dedupeKey} suppresses repeats within the dedupe window. */
public record Notification(UUID id, NotificationType type, NotificationType.Severity severity, String title, String body, Map<String, Object> data,
        String dedupeKey, Instant createdAt, Instant readAt) {

    public Notification {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
