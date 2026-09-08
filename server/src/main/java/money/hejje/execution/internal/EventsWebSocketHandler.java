package money.hejje.execution.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.broker.BrokerSessionChanged;
import money.hejje.orders.OrderFilledEvent;
import money.hejje.orders.OrderStateChangedEvent;
import money.hejje.orders.PositionChangedEvent;
import money.hejje.system.EgressIpStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Client event WebSocket at {@code /ws/events}: pushes order, trade, position, broker and readiness changes. */
@Component
public class EventsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EventsWebSocketHandler.class);

    private final ObjectMapper json;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    EventsWebSocketHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @EventListener
    void onOrderState(OrderStateChangedEvent e) {
        broadcast(Map.of("type", "order", "orderId", e.orderId().toString(), "from", String.valueOf(e.from()), "to", e.to().name()));
    }

    @EventListener
    void onFill(OrderFilledEvent e) {
        broadcast(Map.of("type", "fill", "orderId", e.orderId().toString(), "filledQuantity", e.filledQuantity(),
                "averagePrice", e.averagePrice().toPlainString(), "complete", e.complete()));
    }

    @EventListener
    void onPosition(PositionChangedEvent e) {
        broadcast(Map.of("type", "position", "positionId", e.positionId().toString(), "instrumentId", e.instrumentId().toString(),
                "netQuantity", e.netQuantity()));
    }

    @EventListener
    void onBroker(BrokerSessionChanged e) {
        broadcast(Map.of("type", "broker", "broker", e.broker(), "state", e.current().name(), "detail", String.valueOf(e.detail())));
    }

    @EventListener
    void onEgress(EgressIpStatusChanged e) {
        broadcast(Map.of("type", "readiness", "check", "staticIp", "status", e.current().name()));
    }

    private void broadcast(Map<String, Object> message) {
        String text;
        try {
            text = json.writeValueAsString(message);
        } catch (IOException e) {
            return;
        }
        TextMessage payload = new TextMessage(text);
        for (WebSocketSession session : sessions.values()) {
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(payload);
                    }
                } catch (IOException e) {
                    log.debug("Failed to push event to {}: {}", session.getId(), e.getMessage());
                }
            }
        }
    }
}
