package money.hejje.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import money.hejje.AbstractIntegrationTest;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.common.security.AgentPresets;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** The harness snapshot over REST and the {@code /ws/harness} push (plan M7.4), in PAPER (no replay section). */
class HarnessWebSocketIT extends AbstractIntegrationTest {

    @Autowired ClientCredentialService clients;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test
    @SuppressWarnings("unchecked")
    void theHarnessStreamPushesSnapshots() throws Exception {
        HejjePrincipal admin = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String key = clients.create("harness-" + UUID.randomUUID(), AgentPresets.scopes(AgentPresets.BOT), null, admin).key();

        ResponseEntity<Map> rest1 = rest.exchange("/api/v1/harness/snapshot", HttpMethod.GET, new HttpEntity<>(bearer(key)), Map.class);
        assertThat(rest1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest1.getBody()).containsEntry("mode", "PAPER").containsKeys("header", "context", "tiles", "equity", "positions", "trades", "decisions",
                "log").doesNotContainKey("session");

        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                received.add(json.readValue(m.getPayload(), Map.class));
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/harness?token=" + key)).get(5, TimeUnit.SECONDS);
        Map<String, Object> first = received.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull().containsEntry("type", "snapshot").containsEntry("mode", "PAPER");
        assertThat((Map<String, Object>) first.get("header")).containsKeys("fillSource", "dataHealth", "killSwitch");
        // nothing changed: no second push within a second (it only pushes on change)
        assertThat(received.poll(1, TimeUnit.SECONDS)).isNull();
        session.close();
    }
}
