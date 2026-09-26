package money.hejje.bots.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.bots.BotDecision;
import money.hejje.bots.BotHub;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@code /ws/bot?token=<key>&bot=<id>} (plan M7.3): decision points to the bot, its replies back. The token needs
 * {@code bot:decide}. A reply is {@code {"pointId", "decisions": [...]}}; the server answers with the recorded outcomes
 * ({@code {"type": "decisions", "pointId", "results": [...]}}).
 */
class BotWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BotWebSocketHandler.class);

    private final BotHub hub;
    private final ObjectMapper json;
    private final Map<String, AutoCloseable> connections = new ConcurrentHashMap<>();

    BotWebSocketHandler(BotHub hub, ObjectMapper json) {
        this.hub = hub;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 1 << 20);
        if (!(raw.getPrincipal() instanceof Authentication auth) || !(auth.getPrincipal() instanceof HejjePrincipal p)
                || !p.scopes().contains(ScopeCatalog.BOT_DECIDE)) {
            raw.close(CloseStatus.POLICY_VIOLATION.withReason("bot:decide scope required"));
            return;
        }
        String bot = raw.getUri() == null ? null : UriComponentsBuilder.fromUri(raw.getUri()).build().getQueryParams().getFirst("bot");
        UUID botId;
        try {
            botId = UUID.fromString(bot);
        } catch (RuntimeException e) {
            raw.close(CloseStatus.BAD_DATA.withReason("?bot=<id> required"));
            return;
        }
        if (!p.mayDecideFor(botId)) {
            raw.close(CloseStatus.POLICY_VIOLATION.withReason("key is bound to another bot"));
            return;
        }
        raw.getAttributes().put("botId", botId);
        raw.getAttributes().put("session", session);
        try {
            connections.put(raw.getId(), hub.connect(botId, message -> send(session, message)));
        } catch (IllegalArgumentException e) {
            raw.close(CloseStatus.BAD_DATA.withReason(e.getMessage()));
            return;
        }
        send(session, Map.of("type", "connected", "botId", botId.toString()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws Exception {
        UUID botId = (UUID) raw.getAttributes().get("botId");
        WebSocketSession session = (WebSocketSession) raw.getAttributes().get("session");
        if (botId == null || session == null) {
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            BotDecision.Reply reply = json.readValue(message.getPayload(), BotDecision.Reply.class);
            List<BotDecision> results = hub.answer(botId, reply);
            out.put("type", "decisions");
            out.put("pointId", reply.pointId());
            out.put("results", results);
        } catch (IOException | RuntimeException e) {
            out.put("type", "error");
            out.put("message", e.getMessage());
        }
        send(session, out);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) throws Exception {
        AutoCloseable c = connections.remove(raw.getId());
        if (c != null) {
            c.close();
        }
    }

    private void send(WebSocketSession session, Map<String, Object> message) {
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(message)));
        } catch (IOException e) {
            log.debug("Send to bot failed: {}", e.getMessage());
        }
    }
}
