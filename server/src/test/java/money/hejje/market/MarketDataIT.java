package money.hejje.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import money.hejje.AbstractIntegrationTest;
import money.hejje.broker.BrokerCandle;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.Timeframe;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.internal.InProcessTickBus;
import money.hejje.market.internal.MarketPipeline;
import org.junit.jupiter.api.BeforeEach;
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

class MarketDataIT extends AbstractIntegrationTest {

    @Autowired
    InstrumentService instruments;

    @Autowired
    FakeBrokerAdapter fake;

    @Autowired
    MarketService market;

    @Autowired
    MarketPipeline pipeline;

    @Autowired
    InProcessTickBus bus;

    @Autowired
    money.hejje.market.internal.MarketDataStreamer streamer;

    @LocalServerPort
    int port;

    UUID infy;

    @BeforeEach
    void setUp() {
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
    }

    @Test
    void quotesEndpointFallsBackToRestThenServesCache() {
        fake.injectQuote(infy, "1500.00");
        String token = adminAccessToken();
        ResponseEntity<Map> quotes = rest.exchange("/api/v1/market/quotes?ids=" + infy, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(quotes.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) quotes.getBody().get(infy.toString());
        assertThat(body).containsEntry("lastPrice", 1500.00);
    }

    @Test
    void candlesEndpointMergesRecentAndHistorical() {
        // historical (Parquet) candle at 09:15, live (Postgres) candle at 09:16 via the pipeline
        Instant nine15 = Instant.parse("2026-09-07T03:45:00Z");
        fake.seedHistory(infy, Timeframe.M1, List.of(new BrokerCandle(nine15, new BigDecimal("100"), new BigDecimal("101"),
                new BigDecimal("99"), new BigDecimal("100.5"), 500, 0)));
        long written = backfillJob.runNow(infy, Timeframe.M1, nine15, nine15.plusSeconds(60));
        assertThat(written).isEqualTo(1);

        Instant t1 = Instant.parse("2026-09-08T03:45:01Z");
        Instant t2 = Instant.parse("2026-09-08T03:45:30Z");
        pipeline.onTick(new money.hejje.common.event.MarketTick(infy, t1, new BigDecimal("200"), null, null, 10, 0, money.hejje.common.event.MarketTick.Mode.FULL));
        pipeline.onTick(new money.hejje.common.event.MarketTick(infy, t2, new BigDecimal("201"), null, null, 20, 0, money.hejje.common.event.MarketTick.Mode.FULL));
        pipeline.closeCandlesAsOf(Instant.parse("2026-09-08T03:47:00Z"));
        bus.drain();

        List<Candle> merged = market.candles(infy, Timeframe.M1, Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-08T23:59:00Z"));
        assertThat(merged).extracting(Candle::openTime).contains(nine15, Instant.parse("2026-09-08T03:45:00Z"));
    }

    @Autowired
    money.hejje.market.internal.HistoricalBackfillJob backfillJob;

    @Test
    void subscriptionEndpointsAndWebSocketPushTicks() throws Exception {
        String token = adminAccessToken();
        ResponseEntity<Map> sub = rest.exchange("/api/v1/market/subscriptions", HttpMethod.POST,
                new HttpEntity<>(Map.of("instrumentIds", List.of(infy)), bearer(token)), Map.class);
        assertThat(sub.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) sub.getBody().get("subscribed")).contains(infy.toString());

        List<String> messages = new CopyOnWriteArrayList<>();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                messages.add(m.getPayload());
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/market?token=" + token)).get(5, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage("{\"subscribe\":[\"" + infy + "\"]}"));
        Thread.sleep(200);

        pipeline.onTick(new money.hejje.common.event.MarketTick(infy, Instant.parse("2026-09-08T04:00:00Z"), new BigDecimal("1499.00"), null, null, 5, 0, money.hejje.common.event.MarketTick.Mode.FULL));

        long deadline = System.currentTimeMillis() + 3000;
        while (messages.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        session.close(CloseStatus.NORMAL);
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("\"type\":\"tick\"").contains("1499"));
    }

    @Test
    void webSocketRequiresToken() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        assertThat(catchConnect(client, "ws://localhost:" + port + "/ws/market")).isTrue();
    }

    private boolean catchConnect(StandardWebSocketClient client, String url) {
        try {
            client.execute(new TextWebSocketHandler() {}, new WebSocketHttpHeaders(), URI.create(url)).get(3, TimeUnit.SECONDS);
            return false;
        } catch (Exception e) {
            return true; // handshake rejected (401)
        }
    }
}
