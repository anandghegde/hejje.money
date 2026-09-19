package money.hejje.bots;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import money.hejje.AbstractIntegrationTest;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Timeframe;
import money.hejje.common.event.TickBus;
import money.hejje.common.security.AgentPresets;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import money.hejje.strategy.StrategyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** The {@code /ws/bot} transport and the REST decision endpoint (plan M7.3), in PAPER (no lockstep). */
class BotWebSocketIT extends AbstractIntegrationTest {

    @Autowired BotService bots;
    @Autowired StrategyService strategies;
    @Autowired InstrumentService instruments;
    @Autowired ClientCredentialService clients;
    @Autowired TickBus bus;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test
    @SuppressWarnings("unchecked")
    void aBotKeyReceivesDecisionPointsAndItsRepliesAreRecorded() throws Exception {
        instruments.sync();
        Bot bot = bots.register(new BotService.Registration("wsbot" + (System.nanoTime() % 100000), "1", Bot.Kind.LLM, java.time.LocalDate.of(2026, 1, 1),
                Set.of(ExecutionMode.PAPER), List.of("NSE:INFY"), "5m", null, null), "admin");
        // a new bot has no SIM record, so PAPER is refused (plan M7.5); decision points and HOLD need no deployment
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strategies.deploy(bot.strategyId(), 1, ExecutionMode.PAPER, List.of(), 0, Map.of(), "admin"))
                .isInstanceOf(money.hejje.strategy.StrategyException.Conflict.class).hasMessageContaining("0 of the 20 SIM sessions");
        UUID infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        HejjePrincipal admin = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String key = clients.create("bot-" + bot.name(), AgentPresets.scopes(AgentPresets.BOT), null, admin).key();
        assertThat(AgentPresets.scopes(AgentPresets.BOT)).containsExactlyInAnyOrder("market:read", "strategies:read", "bot:decide");
        {
            // a key without bot:decide is refused
            String research = clients.create("research-" + bot.name(), AgentPresets.scopes(AgentPresets.RESEARCH), null, admin).key();
            BlockingQueue<CloseStatus> refused = new LinkedBlockingQueue<>();
            new StandardWebSocketClient().execute(new TextWebSocketHandler() {
                @Override
                public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                    refused.add(status);
                }
            }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/bot?token=" + research + "&bot=" + bot.id())).get(5, TimeUnit.SECONDS);
            assertThat(refused.poll(5, TimeUnit.SECONDS)).isEqualTo(CloseStatus.POLICY_VIOLATION.withReason("bot:decide scope required"));

            BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
            WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                    received.add(json.readValue(m.getPayload(), Map.class));
                }
            }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/bot?token=" + key + "&bot=" + bot.id())).get(5, TimeUnit.SECONDS);
            assertThat(received.poll(5, TimeUnit.SECONDS)).containsEntry("type", "connected");

            // a closed 5m bar of the bot's universe is a decision point
            java.time.Instant open = LocalDateTime.parse("2026-11-03T09:20:00").atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            bus.publish(new CandleClosedEvent(new Candle(infy, Timeframe.M5, open, new BigDecimal("1500.00"), new BigDecimal("1502.00"), new BigDecimal("1499.00"),
                    new BigDecimal("1501.00"), 12_000, 0, false)));
            Map<String, Object> point = received.poll(10, TimeUnit.SECONDS);
            assertThat(point).isNotNull().containsEntry("type", "decision_point").containsEntry("botId", bot.id().toString())
                    .containsEntry("pointId", open.plusSeconds(300).toString()).containsEntry("mode", "PAPER");
            assertThat((List<Map<String, Object>>) point.get("bars")).singleElement().satisfies(b -> assertThat(b).containsEntry("instrument", "NSE:INFY"));
            assertThat(point).containsKeys("clock", "quotes", "positions", "workingOrders", "regime", "pulse");

            session.sendMessage(new TextMessage(json.writeValueAsString(Map.of("pointId", point.get("pointId"),
                    "decisions", List.of(Map.of("instrument", "NSE:INFY", "action", "HOLD", "confidence", 0.4, "thesis", "range day"))))));
            Map<String, Object> results = received.poll(10, TimeUnit.SECONDS);
            assertThat(results).containsEntry("type", "decisions");
            assertThat((List<Map<String, Object>>) results.get("results")).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("outcome", "NOTED").containsEntry("action", "HOLD").containsEntry("thesis", "range day");
                assertThat(((Number) r.get("latencyMs")).longValue()).isGreaterThanOrEqualTo(0);
            });

            // the same decisions over REST with the bot's key; an unknown action is refused, not an error
            org.springframework.http.HttpHeaders headers = bearer(key);
            ResponseEntity<List> rest1 = rest.exchange("/api/v1/bots/" + bot.id() + "/decisions", HttpMethod.POST, new HttpEntity<>(Map.of("pointId", "manual-1",
                    "decisions", List.of(Map.of("instrument", "NSE:INFY", "action", "BUY_EVERYTHING"))), headers), List.class);
            assertThat(rest1.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((Map<String, Object>) rest1.getBody().get(0)).containsEntry("outcome", "REFUSED");
            ResponseEntity<Map> view = rest.exchange("/api/v1/bots/" + bot.id(), HttpMethod.GET, new HttpEntity<>(bearer(key)), Map.class);
            assertThat(view.getBody()).containsEntry("kind", "LLM").containsEntry("knowledgeCutoff", "2026-01-01");
            assertThat((Map<String, Object>) view.getBody().get("stats")).containsEntry("connected", true).containsEntry("answered", 1);
            session.close();
        }
    }
}
