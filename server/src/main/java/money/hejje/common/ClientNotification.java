package money.hejje.common;

import java.util.Map;

/** A notification for connected clients, broadcast on {@code /ws/events} as {@code {"type": type, ...data}}. */
public record ClientNotification(String type, Map<String, Object> data) {

    public ClientNotification {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
