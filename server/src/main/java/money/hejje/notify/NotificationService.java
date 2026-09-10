package money.hejje.notify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import money.hejje.notify.internal.NotificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Creates notifications and delivers them (plan M5.5): dedupe, the in-app inbox, then per enabled rule and channel —
 * in-app at once; email/Telegram on a background thread, SKIPPED when the channel is not configured, held for the digest
 * above the channel's rate limit. Every attempt is in the delivery log.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationStore store;
    private final List<Channels.Sender> senders;
    private final AuditService audit;
    private final NotifyProperties properties;
    private final HejjeClock clock;
    private final ExecutorService outbox = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notify-outbox");
        t.setDaemon(true);
        return t;
    });

    NotificationService(NotificationStore store, Channels channels, AuditService audit, NotifyProperties properties, HejjeClock clock) {
        this.store = store;
        this.senders = channels.all();
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Channel status: configured or what is missing. */
    public record ChannelStatus(NotificationType.Channel channel, boolean configured, String status, int perMinute) {}

    public Optional<Notification> notify(NotificationType type, String title, String body, Map<String, Object> data, String dedupeKey) {
        return notify(type, type.severity(), title, body, data, dedupeKey);
    }

    public synchronized Optional<Notification> notify(NotificationType type, NotificationType.Severity severity, String title, String body,
            Map<String, Object> data, String dedupeKey) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        Instant now = clock.now();
        if (dedupeKey != null && store.recent(dedupeKey, now.minus(properties.dedupeWindow()))) {
            return Optional.empty();
        }
        Notification n = new Notification(Ids.newId(), type, severity, title, body == null ? "" : body, data, dedupeKey, now, null);
        store.insert(n);
        for (NotificationRule rule : store.rules()) {
            if (!rule.enabled() || rule.eventType() != type || severity.compareTo(rule.minSeverity()) < 0) {
                continue;
            }
            Channels.Sender sender = senders.stream().filter(s -> s.channel() == rule.channel()).findFirst().orElse(null);
            if (sender == null) {
                continue;
            }
            if (rule.channel() == NotificationType.Channel.IN_APP) {
                try {
                    sender.send(n);
                    store.delivery(n.id(), rule.channel(), "SENT", null, now);
                } catch (Exception e) {
                    store.delivery(n.id(), rule.channel(), "FAILED", e.getMessage(), now);
                }
                continue;
            }
            switch (Channels.decide(sender.configured(), store.sentSince(rule.channel(), now.minus(Duration.ofMinutes(1))), sender.perMinute())) {
                case SKIP -> store.delivery(n.id(), rule.channel(), "SKIPPED", sender.status(), now);
                case DIGEST -> store.delivery(n.id(), rule.channel(), "DIGESTED", "above " + sender.perMinute() + "/min; held for the digest", now);
                case SEND -> {
                    UUID delivery = store.delivery(n.id(), rule.channel(), "QUEUED", null, now);
                    outbox.submit(() -> deliver(sender, n, delivery));
                }
            }
        }
        return Optional.of(n);
    }

    private void deliver(Channels.Sender sender, Notification n, UUID delivery) {
        try {
            sender.send(n);
            store.updateDelivery(delivery, "SENT", null, clock.now());
        } catch (Exception e) {
            log.warn("{} delivery of {} failed: {}", sender.channel(), n.type(), e.getMessage());
            store.updateDelivery(delivery, "FAILED", e.getMessage(), clock.now());
        }
    }

    /** Sends one digest per channel for the notifications held by the rate limit; returns how many were folded in. */
    public int flushDigests() {
        int folded = 0;
        for (Channels.Sender sender : senders) {
            if (sender.channel() == NotificationType.Channel.IN_APP) {
                continue;
            }
            List<Map.Entry<UUID, Notification>> held = store.held(sender.channel());
            if (held.isEmpty()) {
                continue;
            }
            String status;
            String detail = null;
            try {
                sender.sendDigest(held.stream().map(Map.Entry::getValue).toList());
                status = "DIGEST_SENT";
            } catch (Exception e) {
                status = "FAILED";
                detail = "digest: " + e.getMessage();
            }
            for (Map.Entry<UUID, Notification> h : held) {
                store.updateDelivery(h.getKey(), status, detail, clock.now());
            }
            folded += held.size();
        }
        return folded;
    }

    /** Waits (briefly) until the background deliveries queued so far are done (tests, the test endpoint). */
    public void awaitOutbox() {
        try {
            outbox.submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("notification outbox did not drain", e);
        }
    }

    public List<Notification> inbox(int limit) {
        return store.inbox(Math.max(1, Math.min(limit, 200)));
    }

    public void markRead(UUID id) {
        store.find(id).orElseThrow(() -> new NoSuchElementException("No notification " + id));
        store.markRead(id, clock.now());
    }

    public List<Map<String, Object>> deliveries(UUID notificationId) {
        return store.deliveries(notificationId);
    }

    public List<NotificationRule> rules() {
        return store.rules();
    }

    public NotificationRule updateRule(UUID id, Boolean enabled, NotificationType.Severity minSeverity, String by) {
        NotificationRule current = store.rule(id).orElseThrow(() -> new NoSuchElementException("No notification rule " + id));
        boolean e = enabled == null ? current.enabled() : enabled;
        NotificationType.Severity min = minSeverity == null ? current.minSeverity() : minSeverity;
        store.updateRule(id, e, min, by, clock.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", current.eventType().name());
        payload.put("channel", current.channel().name());
        payload.put("enabled", e);
        payload.put("minSeverity", min.name());
        audit.record(AuditEvent.of(AuditEventType.NOTIFICATION_RULE_UPDATED, ActorType.USER).withActorId(by).withPayload(payload));
        return store.rule(id).orElseThrow();
    }

    public List<ChannelStatus> channels() {
        List<ChannelStatus> out = new ArrayList<>();
        for (Channels.Sender s : senders) {
            out.add(new ChannelStatus(s.channel(), s.configured(), s.status(), s.perMinute() == Integer.MAX_VALUE ? 0 : s.perMinute()));
        }
        return out;
    }

    /** A TEST notification through every channel with an enabled TEST rule; returns it with its deliveries once sent. */
    public Map<String, Object> test(String by) {
        Notification n = notify(NotificationType.TEST, "Test notification from Hejje", "Requested by " + by + "; if you read this, the channel works.",
                Map.of("requestedBy", by), null).orElseThrow(() -> new IllegalStateException("notifications are disabled (hejje.notify.enabled)"));
        awaitOutbox();
        return Map.of("notification", n, "deliveries", store.deliveries(n.id()));
    }
}
