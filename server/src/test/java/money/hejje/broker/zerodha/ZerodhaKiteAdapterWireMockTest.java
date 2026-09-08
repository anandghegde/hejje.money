package money.hejje.broker.zerodha;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import money.hejje.broker.BrokerCandle;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerModifyRequest;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.broker.BrokerOrderUpdates;
import money.hejje.broker.BrokerPosition;
import money.hejje.broker.BrokerSession;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.Funds;
import money.hejje.broker.Quote;
import money.hejje.broker.internal.BrokerAuthRejected;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.Validity;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ZerodhaKiteAdapterWireMockTest {

    static final String API_KEY = "test-api-key";
    static final String API_SECRET = "test-api-secret-value";
    static final String ACCESS_TOKEN = "acc-secret-token-xyz";
    static final UUID NIFTY_FUT = UUID.fromString("0192a000-0000-7000-8000-000000000001");
    static final UUID INFY = UUID.fromString("0192a000-0000-7000-8000-000000000002");

    static WireMockServer wiremock;
    ZerodhaKiteAdapter adapter;
    List<Object> published = new CopyOnWriteArrayList<>();
    ListAppender<ILoggingEvent> logs;

    static final BrokerInstrumentResolver RESOLVER = new BrokerInstrumentResolver() {
        final Map<UUID, BrokerInstrumentRef> refs = Map.of(
                NIFTY_FUT, new BrokerInstrumentRef(NIFTY_FUT, Exchange.NFO, InstrumentType.FUT, "13368834", "NIFTY26SEPFUT", "NFO", 75, new BigDecimal("0.05")),
                INFY, new BrokerInstrumentRef(INFY, Exchange.NSE, InstrumentType.EQ, "408065", "INFY", "NSE", 1, new BigDecimal("0.05")));

        @Override
        public Optional<BrokerInstrumentRef> forInstrument(UUID instrumentId, String broker) {
            return Optional.ofNullable(refs.get(instrumentId));
        }

        @Override
        public Optional<UUID> byBrokerToken(String broker, String brokerToken) {
            return refs.values().stream().filter(r -> r.brokerToken().equals(brokerToken)).map(BrokerInstrumentRef::instrumentId).findFirst();
        }

        @Override
        public Optional<UUID> byTradingSymbol(String broker, String exchangeSegment, String tradingSymbol) {
            return refs.values().stream().filter(r -> r.exchangeSegment().equals(exchangeSegment) && r.tradingSymbol().equals(tradingSymbol))
                    .map(BrokerInstrumentRef::instrumentId).findFirst();
        }
    };

    @BeforeAll
    static void startServer() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopServer() {
        wiremock.stop();
        KiteClientFactory.applyBaseUrl(KiteClientFactory.DEFAULT_BASE_URL);
    }

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
        KiteProperties props = new KiteProperties(API_KEY, API_SECRET, wiremock.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2));
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);
        adapter = new ZerodhaKiteAdapter(KiteClientFactory.create(props), props, RESOLVER, new BrokerOrderUpdates(), clock, published::add);
        logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logs);
        root.setLevel(Level.INFO);
    }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    static String error(String type, String message) {
        return "{\"status\":\"error\",\"message\":\"" + message + "\",\"error_type\":\"" + type + "\"}";
    }

    void login() throws Exception {
        wiremock.stubFor(post(urlEqualTo("/session/token")).willReturn(json(200, """
                {"status":"success","data":{"user_id":"AB1234","user_name":"Test User","email":"t@example.com","broker":"ZERODHA",
                 "user_type":"individual","access_token":"%s","public_token":"pub-token","login_time":"2026-09-08 08:30:00",
                 "products":["MIS","CNC","NRML"],"exchanges":["NSE","NFO"],"order_types":["MARKET","LIMIT"],"api_key":"%s"}}
                """.formatted(ACCESS_TOKEN, API_KEY))));
        adapter.authenticate("req-token-1");
    }

    @Test
    void tokenExchangeSendsCorrectChecksumAndInstallsSession() throws Exception {
        login();
        String expectedChecksum = sha256(API_KEY + "req-token-1" + API_SECRET);
        wiremock.verify(postRequestedFor(urlEqualTo("/session/token"))
                .withHeader("X-Kite-Version", equalTo("3"))
                .withRequestBody(containing("api_key=" + API_KEY))
                .withRequestBody(containing("request_token=req-token-1"))
                .withRequestBody(containing("checksum=" + expectedChecksum)));
        assertThat(adapter.sessionState()).isEqualTo(BrokerSessionState.CONNECTED);

        wiremock.stubFor(get(urlEqualTo("/user/profile")).willReturn(json(200,
                "{\"status\":\"success\",\"data\":{\"user_name\":\"Test User\",\"email\":\"t@example.com\",\"exchanges\":[\"NSE\"],\"products\":[\"MIS\"]}}")));
        assertThat(adapter.getProfile().brokerUserId()).isEqualTo("AB1234");
        wiremock.verify(getRequestedFor(urlEqualTo("/user/profile")).withHeader("Authorization", equalTo("token " + API_KEY + ":" + ACCESS_TOKEN)));
    }

    @Test
    void loginReturnsSessionWithIstLoginTime() throws Exception {
        wiremock.stubFor(post(urlEqualTo("/session/token")).willReturn(json(200, """
                {"status":"success","data":{"user_id":"AB1234","user_name":"Test User","access_token":"%s","public_token":"pub",
                 "login_time":"2026-09-08 08:30:00","products":[],"exchanges":[],"order_types":[]}}
                """.formatted(ACCESS_TOKEN))));
        BrokerSession session = adapter.authenticate("rt");
        assertThat(session.brokerUserId()).isEqualTo("AB1234");
        assertThat(session.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(session.establishedAt()).isEqualTo(Instant.parse("2026-09-08T03:00:00Z"));
        assertThat(session.toString()).doesNotContain(ACCESS_TOKEN);
    }

    @Test
    void secretsNeverAppearInLogs() throws Exception {
        login();
        wiremock.stubFor(post(urlPathEqualTo("/orders/regular")).willReturn(json(403, error("TokenException", "Token is invalid or has expired."))));
        assertThatThrownBy(() -> adapter.placeOrder(marketBuy())).isInstanceOf(BrokerException.class);
        String allLogs = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(allLogs).isNotEmpty();
        assertThat(allLogs).doesNotContain(API_SECRET).doesNotContain(ACCESS_TOKEN);
        for (ILoggingEvent event : logs.list) {
            if (event.getArgumentArray() != null) {
                for (Object arg : event.getArgumentArray()) {
                    assertThat(String.valueOf(arg)).doesNotContain(API_SECRET).doesNotContain(ACCESS_TOKEN);
                }
            }
        }
    }

    static BrokerOrderRequest marketBuy() {
        return new BrokerOrderRequest(NIFTY_FUT, Side.BUY, Quantity.of(75), OrderType.MARKET, Product.NRML, null, null, Validity.DAY, "hj12345");
    }

    @Test
    void placeModifyCancelMapping() throws Exception {
        login();
        wiremock.stubFor(post(urlPathEqualTo("/orders/regular")).willReturn(json(200, "{\"status\":\"success\",\"data\":{\"order_id\":\"151220000000001\"}}")));
        BrokerOrderRef ref = adapter.placeOrder(new BrokerOrderRequest(NIFTY_FUT, Side.SELL, Quantity.of(150), OrderType.LIMIT, Product.NRML,
                Price.of("24950.00"), null, Validity.DAY, "hjabc"));
        assertThat(ref.brokerOrderId()).isEqualTo("151220000000001");
        wiremock.verify(postRequestedFor(urlPathEqualTo("/orders/regular"))
                .withRequestBody(containing("exchange=NFO"))
                .withRequestBody(containing("tradingsymbol=NIFTY26SEPFUT"))
                .withRequestBody(containing("transaction_type=SELL"))
                .withRequestBody(containing("quantity=150"))
                .withRequestBody(containing("order_type=LIMIT"))
                .withRequestBody(containing("product=NRML"))
                .withRequestBody(containing("price=24950.0"))
                .withRequestBody(containing("validity=DAY"))
                .withRequestBody(containing("tag=hjabc")));

        wiremock.stubFor(put(urlPathEqualTo("/orders/regular/151220000000001")).willReturn(json(200, "{\"status\":\"success\",\"data\":{\"order_id\":\"151220000000001\"}}")));
        adapter.modifyOrder(ref, new BrokerModifyRequest(null, null, Price.of("24940.00"), null, null));
        wiremock.verify(putRequestedFor(urlPathEqualTo("/orders/regular/151220000000001")).withRequestBody(containing("price=24940.0")));

        wiremock.stubFor(delete(urlPathEqualTo("/orders/regular/151220000000001")).willReturn(json(200, "{\"status\":\"success\",\"data\":{\"order_id\":\"151220000000001\"}}")));
        assertThat(adapter.cancelOrder(ref).brokerOrderId()).isEqualTo("151220000000001");
        wiremock.verify(deleteRequestedFor(urlPathEqualTo("/orders/regular/151220000000001")));
    }

    @Test
    void getOrdersTradesPositionsFundsMapping() throws Exception {
        login();
        wiremock.stubFor(get(urlEqualTo("/orders")).willReturn(json(200, """
                {"status":"success","data":[
                 {"order_id":"151220000000001","exchange":"NFO","tradingsymbol":"NIFTY26SEPFUT","transaction_type":"BUY","quantity":75,
                  "filled_quantity":75,"pending_quantity":0,"average_price":24980.5,"order_type":"MARKET","product":"NRML","price":0,
                  "trigger_price":0,"validity":"DAY","status":"COMPLETE","status_message":null,"tag":"hj12345","parent_order_id":null,
                  "order_timestamp":"2026-09-08 09:20:01","exchange_update_timestamp":"2026-09-08 09:20:02","variety":"regular"},
                 {"order_id":"151220000000002","exchange":"NSE","tradingsymbol":"UNKNOWNSYM","transaction_type":"SELL","quantity":10,
                  "filled_quantity":0,"pending_quantity":10,"average_price":0,"order_type":"LIMIT","product":"MIS","price":101.5,
                  "trigger_price":0,"validity":"DAY","status":"OPEN","status_message":"","tag":null,"order_timestamp":"2026-09-08 09:21:00"}]}
                """)));
        List<BrokerOrder> orders = adapter.getOrders();
        assertThat(orders).hasSize(2);
        BrokerOrder first = orders.get(0);
        assertThat(first.instrumentId()).isEqualTo(NIFTY_FUT);
        assertThat(first.status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        assertThat(first.filledQuantity()).isEqualTo(75);
        assertThat(first.averagePrice()).isEqualByComparingTo("24980.50");
        assertThat(first.placedAt()).isEqualTo(Instant.parse("2026-09-08T03:50:01Z"));
        assertThat(first.updatedAt()).isEqualTo(Instant.parse("2026-09-08T03:50:02Z"));
        assertThat(first.tag()).isEqualTo("hj12345");
        BrokerOrder second = orders.get(1);
        assertThat(second.instrumentId()).isNull();
        assertThat(second.status()).isEqualTo(BrokerOrderStatus.OPEN);
        assertThat(second.limitPrice()).isEqualByComparingTo("101.50");

        wiremock.stubFor(get(urlEqualTo("/orders/151220000000001")).willReturn(json(200, """
                {"status":"success","data":[
                 {"order_id":"151220000000001","status":"PUT ORDER REQ RECEIVED","exchange":"NFO","tradingsymbol":"NIFTY26SEPFUT","transaction_type":"BUY","quantity":75},
                 {"order_id":"151220000000001","status":"OPEN","exchange":"NFO","tradingsymbol":"NIFTY26SEPFUT","transaction_type":"BUY","quantity":75}]}
                """)));
        assertThat(adapter.getOrder(new BrokerOrderRef("151220000000001")).status()).isEqualTo(BrokerOrderStatus.OPEN);

        wiremock.stubFor(get(urlEqualTo("/trades")).willReturn(json(200, """
                {"status":"success","data":[{"trade_id":"1001","order_id":"151220000000001","exchange":"NFO","tradingsymbol":"NIFTY26SEPFUT",
                 "instrument_token":13368834,"product":"NRML","average_price":24980.5,"quantity":75,"fill_timestamp":"2026-09-08 09:20:02",
                 "transaction_type":"BUY"}]}
                """)));
        assertThat(adapter.getTrades()).singleElement().satisfies(t -> {
            assertThat(t.instrumentId()).isEqualTo(NIFTY_FUT);
            assertThat(t.price()).isEqualByComparingTo("24980.50");
            assertThat(t.side()).isEqualTo(Side.BUY);
            assertThat(t.ts()).isEqualTo(Instant.parse("2026-09-08T03:50:02Z"));
        });

        wiremock.stubFor(get(urlEqualTo("/portfolio/positions")).willReturn(json(200, """
                {"status":"success","data":{"net":[{"tradingsymbol":"NIFTY26SEPFUT","exchange":"NFO","instrument_token":13368834,"product":"NRML",
                 "quantity":75,"overnight_quantity":0,"multiplier":1,"average_price":24980.5,"close_price":0,"last_price":24990.0,"value":-1873537.5,
                 "pnl":712.5,"m2m":712.5,"unrealised":712.5,"realised":0,"buy_quantity":75,"buy_price":24980.5,"buy_value":1873537.5,"buy_m2m":1873537.5,
                 "sell_quantity":0,"sell_price":0,"sell_value":0,"sell_m2m":0,"day_buy_quantity":75,"day_buy_price":24980.5,"day_buy_value":1873537.5,
                 "day_sell_quantity":0,"day_sell_price":0,"day_sell_value":0}],"day":[]}}
                """)));
        List<BrokerPosition> positions = adapter.getPositions();
        assertThat(positions).singleElement().satisfies(p -> {
            assertThat(p.instrumentId()).isEqualTo(NIFTY_FUT);
            assertThat(p.netQuantity()).isEqualTo(75);
            assertThat(p.unrealizedPnl().paise()).isEqualTo(71250);
            assertThat(p.dayBuyQuantity()).isEqualTo(75);
        });

        wiremock.stubFor(get(urlEqualTo("/user/margins")).willReturn(json(200, """
                {"status":"success","data":{"equity":{"enabled":true,"net":99725.05,"available":{"adhoc_margin":0,"cash":245431.6,"opening_balance":245431.6,
                 "live_balance":99725.05,"collateral":0,"intraday_payin":0},"utilised":{"debits":145706.55,"exposure":38981.25,"m2m_realised":761.7,
                 "m2m_unrealised":0,"option_premium":0,"payout":0,"span":101989,"holding_sales":0,"turnover":0}},"commodity":{"enabled":false}}}
                """)));
        Funds funds = adapter.getFunds();
        assertThat(funds.availableCash().toRupeesString()).isEqualTo("99725.05");
        assertThat(funds.usedMargin().toRupeesString()).isEqualTo("145706.55");
        assertThat(funds.net().toRupeesString()).isEqualTo("99725.05");
    }

    @Test
    void quotesAndHistoryUseIstAndTokens() throws Exception {
        login();
        wiremock.stubFor(get(urlPathEqualTo("/quote")).willReturn(json(200, """
                {"status":"success","data":{"NSE:INFY":{"instrument_token":408065,"timestamp":"2026-09-08 10:00:00","last_trade_time":"2026-09-08 09:59:59",
                 "last_price":1498.2,"last_quantity":5,"buy_quantity":1000,"sell_quantity":900,"volume":123456,"average_price":1495.0,"oi":0,
                 "ohlc":{"open":1490,"high":1500,"low":1485,"close":1488},"depth":{"buy":[{"price":1498.1,"quantity":10,"orders":1}],
                 "sell":[{"price":1498.3,"quantity":12,"orders":2}]}}}}
                """)));
        List<Quote> quotes = adapter.getQuote(Set.of(INFY));
        wiremock.verify(getRequestedFor(urlPathEqualTo("/quote")).withQueryParam("i", equalTo("NSE:INFY")));
        assertThat(quotes).singleElement().satisfies(q -> {
            assertThat(q.instrumentId()).isEqualTo(INFY);
            assertThat(q.lastPrice()).isEqualByComparingTo("1498.20");
            assertThat(q.bid()).isEqualByComparingTo("1498.10");
            assertThat(q.ask()).isEqualByComparingTo("1498.30");
            assertThat(q.volume()).isEqualTo(123456);
            assertThat(q.ts()).isEqualTo(Instant.parse("2026-09-08T04:30:00Z"));
        });

        wiremock.stubFor(get(urlPathEqualTo("/instruments/historical/13368834/5minute")).willReturn(json(200, """
                {"status":"success","data":{"candles":[["2026-09-08T09:15:00+0530",24950,24980.5,24940,24975.25,12345,1000000],
                 ["2026-09-08T09:20:00+0530",24975.25,24990,24970,24985,2345,1000500]]}}
                """)));
        List<BrokerCandle> candles = adapter.getHistory(NIFTY_FUT, Timeframe.M5, Instant.parse("2026-09-08T03:45:00Z"), Instant.parse("2026-09-08T10:00:00Z"));
        wiremock.verify(getRequestedFor(urlPathEqualTo("/instruments/historical/13368834/5minute"))
                .withQueryParam("from", equalTo("2026-09-08 09:15:00"))
                .withQueryParam("to", equalTo("2026-09-08 15:30:00"))
                .withQueryParam("oi", equalTo("1")));
        assertThat(candles).hasSize(2);
        assertThat(candles.get(0).openTime()).isEqualTo(Instant.parse("2026-09-08T03:45:00Z"));
        assertThat(candles.get(0).close()).isEqualByComparingTo("24975.25");
        assertThat(candles.get(1).oi()).isEqualTo(1000500);
    }

    @Test
    void everyErrorFamilyMapsToKind() throws Exception {
        login();
        record Case(int http, String type, BrokerException.Kind kind) {}
        List<Case> cases = List.of(
                new Case(400, "InputException", BrokerException.Kind.INPUT),
                new Case(400, "OrderException", BrokerException.Kind.REJECTED),
                new Case(429, "NetworkException", BrokerException.Kind.RATE_LIMIT),
                new Case(503, "NetworkException", BrokerException.Kind.NETWORK),
                new Case(500, "GeneralException", BrokerException.Kind.NETWORK),
                new Case(502, "DataException", BrokerException.Kind.NETWORK),
                new Case(400, "DataException", BrokerException.Kind.UNKNOWN),
                new Case(403, "PermissionException", BrokerException.Kind.AUTH));
        for (Case c : cases) {
            wiremock.resetAll();
            wiremock.stubFor(post(urlPathEqualTo("/orders/regular")).willReturn(json(c.http(), error(c.type(), "msg for " + c.type()))));
            assertThatThrownBy(() -> adapter.placeOrder(marketBuy()))
                    .isInstanceOfSatisfying(BrokerException.class, e -> {
                        assertThat(e.kind()).as(c.type()).isEqualTo(c.kind());
                        assertThat(e.brokerMessage()).isEqualTo("msg for " + c.type());
                    });
        }
        // PermissionException above already flipped the session; re-login for the remaining checks.
        login();
        wiremock.stubFor(post(urlPathEqualTo("/orders/regular")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"success\",\"data\":{\"order_id\":\"1\"}}").withFixedDelay(3000)));
        assertThatThrownBy(() -> adapter.placeOrder(marketBuy())).isInstanceOfSatisfying(BrokerException.class, e -> {
            assertThat(e.kind()).isEqualTo(BrokerException.Kind.TIMEOUT);
            assertThat(e.outcomeUnknown()).isTrue();
        });
        assertThat(adapter.sessionState()).isEqualTo(BrokerSessionState.CONNECTED);
    }

    @Test
    void tokenErrorDisconnectsAndSignals() throws Exception {
        login();
        published.clear();
        wiremock.stubFor(get(urlEqualTo("/orders")).willReturn(json(403, error("TokenException", "Token is invalid or has expired."))));
        assertThatThrownBy(adapter::getOrders).isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.AUTH));
        assertThat(adapter.sessionState()).isEqualTo(BrokerSessionState.DISCONNECTED);
        assertThat(published).hasSize(1).first().isInstanceOf(BrokerAuthRejected.class);
    }
}
