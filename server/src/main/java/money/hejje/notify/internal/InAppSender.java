package money.hejje.notify.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import money.hejje.common.ClientNotification;
import money.hejje.notify.Notification;
import money.hejje.notify.NotificationType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** In-app: the inbox row already exists; push it to connected clients on {@code /ws/events}. */
@Component
class InAppSender implements money.hejje.notify.Channels.Sender {

    private final ApplicationEventPublisher events;

    InAppSender(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public NotificationType.Channel channel() {
        return NotificationType.Channel.IN_APP;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String status() {
        return "always on";
    }

    @Override
    public int perMinute() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void sendDigest(java.util.List<Notification> held) throws Exception {
        send(Digest.of(held));
    }

    @Override
    public void send(Notification n) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", n.id().toString());
        data.put("notificationType", n.type().name());
        data.put("severity", n.severity().name());
        data.put("title", n.title());
        data.put("body", n.body());
        events.publishEvent(new ClientNotification("notification", data));
    }
}
