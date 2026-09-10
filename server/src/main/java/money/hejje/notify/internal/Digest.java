package money.hejje.notify.internal;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import money.hejje.notify.Notification;
import money.hejje.notify.NotificationType;

/** Folds rate-limited notifications into one message (plan M5.5). */
final class Digest {

    private Digest() {
    }

    static Notification of(List<Notification> held) {
        NotificationType.Severity worst = held.stream().map(Notification::severity).max(Comparator.naturalOrder()).orElse(NotificationType.Severity.INFO);
        String body = held.stream().sorted(Comparator.comparing(Notification::createdAt)).map(n -> "• [" + n.severity() + "] " + n.title())
                .collect(Collectors.joining("\n"));
        return new Notification(UUID.randomUUID(), NotificationType.TEST, worst, "Hejje: " + held.size() + " notification(s) held by the rate limit", body,
                Map.of("count", held.size()), null, Instant.now(), null);
    }
}
