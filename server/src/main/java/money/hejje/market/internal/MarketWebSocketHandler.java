package money.hejje.market.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.common.event.MarketEvent;
import money.hejje.common.event.MarketTick;
import money.hejje.common.event.TickBus;
import money.hejje.market.CandleClosedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Client market WebSocket at {@code /ws/market}. The client sends {@code {"subscribe":[ids]}} / {@code {"unsubscribe":[ids]}};
 * the server pushes {@code {"type":"tick",...}} and {@code {"type":"candle",...}} for subscribed instruments. Auth is by
 * {@code ?token=} (BearerAuthenticationFilter). One tick-bus subscription is shared; per-session filtering is by id set.
 */
class MarketWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketWebSocketHandler.class);

    private final ObjectMapper json;
    private final java.util.Map<String, Set<UUID>> interests = new ConcurrentHashMap<>();
    private final java.util.Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    MarketWebSocketHandler(TickBus bus, ObjectMapper json) {
        this.json = json;
        bus.subscribe(this::onEvent);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        interests.put(session.getId(), ConcurrentHashMap.newKeySet());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        interests.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            var node = json.readTree(message.getPayload());
            Set<UUID> set = interests.get(session.getId());
            if (set == null) {
                return;
            }
            if (node.has("subscribe")) {
                node.get("subscribe").forEach(n -> set.add(UUID.fromString(n.asText())));
            }
            if (node.has("unsubscribe")) {
                node.get("unsubscribe").forEach(n -> set.remove(UUID.fromString(n.asText())));
            }
        } catch (RuntimeException | IOException e) {
            log.debug("Bad market WS message: {}", e.getMessage());
        }
    }

    private void onEvent(MarketEvent event) {
        UUID instrumentId;
        Object payload;
        if (event instanceof MarketTick tick) {
            instrumentId = tick.instrumentId();
            payload = new TickMessage("tick", tick.instrumentId(), tick.ts(), tick.lastPrice(), tick.bid(), tick.ask(), tick.volume(), tick.oi());
        } else if (event instanceof CandleClosedEvent closed) {
            instrumentId = closed.candle().instrumentId();
            payload = new CandleMessage("candle", closed.candle());
        } else {
            return;
        }
        String text;
        try {
            text = json.writeValueAsString(payload);
        } catch (IOException e) {
            return;
        }
        TextMessage message = new TextMessage(text);
        for (var entry : interests.entrySet()) {
            if (entry.getValue().contains(instrumentId)) {
                send(entry.getKey(), message);
            }
        }
    }

    private void send(String sessionId, TextMessage message) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (IOException e) {
            log.debug("Failed to send to WS session {}: {}", sessionId, e.getMessage());
        }
    }

    record TickMessage(String type, UUID instrumentId, java.time.Instant ts, java.math.BigDecimal lastPrice,
            java.math.BigDecimal bid, java.math.BigDecimal ask, long volume, long oi) {}

    record CandleMessage(String type, money.hejje.market.Candle candle) {}
}
