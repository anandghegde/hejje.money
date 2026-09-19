package money.hejje.harness.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.harness.HarnessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@code /ws/harness?token=&bot=&session=} (plan M7.4): pushes {@code {"type": "snapshot", ...}} on connect and whenever it
 * changes, at most four times a second (wall time: the stream is presentation, not trading). Needs {@code market:read}.
 */
@Configuration
@EnableWebSocket
class HarnessWebSocket extends TextWebSocketHandler implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(HarnessWebSocket.class);
    static final long PUSH_MILLIS = 250;

    private final HarnessService harness;
    private final ObjectMapper json;
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "harness-push");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> pushes = new ConcurrentHashMap<>();

    HarnessWebSocket(HarnessService harness, ObjectMapper json) {
        this.harness = harness;
        this.json = json;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/ws/harness").setAllowedOriginPatterns("*");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        if (!(raw.getPrincipal() instanceof Authentication auth) || !(auth.getPrincipal() instanceof HejjePrincipal p)
                || !p.scopes().contains(ScopeCatalog.MARKET_READ)) {
            raw.close(CloseStatus.POLICY_VIOLATION.withReason("market:read scope required"));
            return;
        }
        var params = raw.getUri() == null ? null : UriComponentsBuilder.fromUri(raw.getUri()).build().getQueryParams();
        UUID bot = uuid(params == null ? null : params.getFirst("bot"));
        UUID session = uuid(params == null ? null : params.getFirst("session"));
        WebSocketSession out = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 4 << 20);
        String[] last = {null};
        pushes.put(raw.getId(), pusher.scheduleWithFixedDelay(() -> {
            try {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("type", "snapshot");
                snapshot.putAll(harness.snapshot(bot, session));
                String text = json.writeValueAsString(snapshot);
                String comparable = text.replaceAll("\"clock\":\"[^\"]*\"", "").replaceAll("\"nextDecisionInSeconds\":\\d+", "");
                if (!Objects.equals(comparable, last[0]) || last[0] == null) {
                    last[0] = comparable;
                    out.sendMessage(new TextMessage(text));
                }
            } catch (Exception e) {
                log.debug("Harness push failed: {}", e.getMessage());
            }
        }, 0, PUSH_MILLIS, TimeUnit.MILLISECONDS));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        ScheduledFuture<?> f = pushes.remove(raw.getId());
        if (f != null) {
            f.cancel(false);
        }
    }

    private static UUID uuid(String text) {
        try {
            return text == null || text.isBlank() ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
