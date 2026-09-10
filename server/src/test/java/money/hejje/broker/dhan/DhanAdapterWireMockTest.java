package money.hejje.broker.dhan;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
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
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import money.hejje.broker.BrokerCandle;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerInstrument;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerModifyRequest;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.BrokerOrderStatus;
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

/** The Dhan adapter against a WireMock DhanHQ v2 (plan M5.6): request shapes, field mapping, errors, session, secrets. */
class DhanAdapterWireMockTest {

    static final String CLIENT = "1100003626";
    static final String APP_SECRET = "app-secret-value-xyz";
    static final String TOKEN = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIxMTAwMDAzNjI2In0.c2VjcmV0LXNpZ25hdHVyZQ";
    static final UUID INFY = UUID.fromString("0192a000-0000-7000-8000-0000000000d1");
    static final UUID NIFTY_FUT = UUID.fromString("0192a000-0000-7000-8000-0000000000d2");

    static final BrokerInstrumentResolver RESOLVER = new BrokerInstrumentResolver() {
        final Map<UUID, BrokerInstrumentRef> refs = Map.of(
                INFY, new BrokerInstrumentRef(INFY, Exchange.NSE, InstrumentType.EQ, "NSE_EQ:1594", "INFY", "NSE_EQ", 1, new BigDecimal("0.05")),
                NIFTY_FUT, new BrokerInstrumentRef(NIFTY_FUT, Exchange.NFO, InstrumentType.FUT, "NSE_FNO:52175", "NIFTY-Sep2026-FUT", "NSE_FNO", 75,
                        new BigDecimal("0.10")));

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
            return Optional.empty();
        }
    };

    static WireMockServer wiremock;
    DhanAdapter adapter;
    List<Object> published = new CopyOnWriteArrayList<>();
    ListAppender<ILoggingEvent> logs;

    @BeforeAll
    static void start() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stop() {
        wiremock.stop();
    }

    static DhanProperties props(String baseUrl) {
        return new DhanProperties(CLIENT, "app-id-1", APP_SECRET, baseUrl + "/v2", baseUrl, baseUrl + "/scrip.csv", Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);
        adapter = new DhanAdapter(props(wiremock.baseUrl()), RESOLVER, clock, published::add);
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

    static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    static String error(String code, String type, String message) {
        return "{\"errorType\":\"" + type + "\",\"errorCode\":\"" + code + "\",\"errorMessage\":\"" + message + "\"}";
    }

    static BrokerOrderRequest limitBuy(UUID instrument, int qty, String price) {
        return new BrokerOrderRequest(instrument, Side.BUY, Quantity.of(qty), OrderType.LIMIT, Product.MIS, Price.of(price), null, Validity.DAY, "abc123");
    }

    static final String ORDER_OPEN = """
            {"dhanClientId":"1100003626","orderId":"112111182198","correlationId":"abc123","orderStatus":"PENDING","transactionType":"BUY",
             "exchangeSegment":"NSE_EQ","productType":"INTRADAY","orderType":"LIMIT","validity":"DAY","tradingSymbol":"INFY","securityId":"1594",
             "quantity":10,"disclosedQuantity":0,"price":1500.5,"triggerPrice":0,"afterMarketOrder":false,"createTime":"2026-09-08 09:30:00",
             "updateTime":"2026-09-08 09:30:01","exchangeTime":"2026-09-08 09:30:01","omsErrorCode":"","omsErrorDescription":"","remainingQuantity":10,
             "averageTradedPrice":0,"filledQty":0}
            """;

    @Test
    void consentLoginExchangesTheTokenIdAndSecretsNeverReachTheLogs() {
        wiremock.stubFor(post(urlEqualTo("/app/generate-consent?client_id=" + CLIENT)).withHeader("app_id", equalTo("app-id-1"))
                .withHeader("app_secret", equalTo(APP_SECRET)).willReturn(json(200, "{\"consentAppId\":\"c-940b\",\"consentAppStatus\":\"GENERATED\",\"status\":\"success\"}")));
        assertThat(adapter.loginUrl()).isEqualTo(wiremock.baseUrl() + "/login/consentApp-login?consentAppId=c-940b");

        wiremock.stubFor(get(urlEqualTo("/app/consumeApp-consent?tokenId=tok-1")).withHeader("app_secret", equalTo(APP_SECRET)).willReturn(json(200, """
                {"dhanClientId":"1100003626","dhanClientName":"Test","dhanClientUcc":"X","givenPowerOfAttorney":true,"accessToken":"%s",
                 "expiryTime":"2026-09-09T10:00:00"}""".formatted(TOKEN))));
        BrokerSession session = adapter.authenticate("tok-1");
        assertThat(session.brokerUserId()).isEqualTo(CLIENT);
        assertThat(session.toString()).doesNotContain(TOKEN);
        assertThat(adapter.sessionState()).isEqualTo(BrokerSessionState.CONNECTED);

        wiremock.stubFor(get(urlEqualTo("/v2/fundlimit")).willReturn(json(200, """
                {"dhanClientId":"1100003626","availabelBalance":98440.0,"sodLimit":113642,"collateralAmount":0.0,"receiveableAmount":0.0,
                 "utilizedAmount":15202.0,"blockedPayoutAmount":0.0,"withdrawableBalance":98310.0}""")));
        Funds funds = adapter.getFunds();
        assertThat(funds.availableCash().toRupees()).isEqualByComparingTo("98440.00");
        assertThat(funds.usedMargin().toRupees()).isEqualByComparingTo("15202.00");
        wiremock.verify(getRequestedFor(urlEqualTo("/v2/fundlimit")).withHeader("access-token", equalTo(TOKEN)).withHeader("client-id", equalTo(CLIENT)));

        wiremock.stubFor(get(urlEqualTo("/v2/orders")).willReturn(json(401, error("DH-901", "Invalid_Authentication", "Client ID or user generated access token is invalid or expired."))));
        assertThatThrownBy(adapter::getOrders).isInstanceOf(BrokerException.class);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(m -> m.contains(TOKEN) || m.contains(APP_SECRET));
    }

    @Test
    void aPastedAccessTokenIsCheckedWithTheProfile() {
        wiremock.stubFor(get(urlEqualTo("/v2/profile")).withHeader("access-token", equalTo(TOKEN)).willReturn(json(200, """
                {"dhanClientId":"1100003626","tokenValidity":"09/09/2026 10:00","activeSegment":"Equity, Derivative","ddpi":"Active","mtf":"Active",
                 "dataPlan":"Active","dataValidity":"2026-12-05 09:37:52.0"}""")));
        assertThat(adapter.authenticate(TOKEN).brokerUserId()).isEqualTo(CLIENT);
        assertThat(adapter.getProfile().exchanges()).containsExactly("NSE", "BSE", "NFO", "BFO");
    }

    @Test
    void placeModifyAndCancelSendTheDocumentedRequests() {
        adapter.restoreSession(TOKEN);
        wiremock.stubFor(post(urlEqualTo("/v2/orders")).willReturn(json(200, "{\"orderId\":\"112111182198\",\"orderStatus\":\"PENDING\"}")));
        BrokerOrderRef ref = adapter.placeOrder(limitBuy(INFY, 10, "1500.50"));
        assertThat(ref.brokerOrderId()).isEqualTo("112111182198");
        wiremock.verify(postRequestedFor(urlEqualTo("/v2/orders")).withRequestBody(equalToJson("""
                {"dhanClientId":"1100003626","correlationId":"abc123","transactionType":"BUY","exchangeSegment":"NSE_EQ","productType":"INTRADAY",
                 "orderType":"LIMIT","validity":"DAY","securityId":"1594","quantity":10,"disclosedQuantity":0,"price":1500.50,"afterMarketOrder":false}""")));

        wiremock.stubFor(get(urlEqualTo("/v2/orders/112111182198")).willReturn(json(200, ORDER_OPEN)));
        wiremock.stubFor(put(urlEqualTo("/v2/orders/112111182198")).willReturn(json(200, "{\"orderId\":\"112111182198\",\"orderStatus\":\"TRANSIT\"}")));
        adapter.modifyOrder(ref, new BrokerModifyRequest(null, null, Price.of("1502.00"), null, null));
        wiremock.verify(putRequestedFor(urlEqualTo("/v2/orders/112111182198")).withRequestBody(equalToJson("""
                {"dhanClientId":"1100003626","orderId":"112111182198","orderType":"LIMIT","quantity":10,"price":1502.00,"disclosedQuantity":0,"validity":"DAY"}""")));

        wiremock.stubFor(delete(urlEqualTo("/v2/orders/112111182198")).willReturn(aResponse().withStatus(202)));
        assertThat(adapter.cancelOrder(ref).brokerOrderId()).isEqualTo("112111182198");

        BrokerOrderRequest slm = new BrokerOrderRequest(NIFTY_FUT, Side.SELL, Quantity.of(75), OrderType.SL_M, Product.NRML, null, Price.of("24900.00"),
                Validity.DAY, "stop1");
        adapter.placeOrder(slm);
        wiremock.verify(postRequestedFor(urlEqualTo("/v2/orders")).withRequestBody(matchingJsonPath("$.orderType", equalTo("STOP_LOSS_MARKET")))
                .withRequestBody(matchingJsonPath("$.productType", equalTo("MARGIN"))).withRequestBody(matchingJsonPath("$.exchangeSegment", equalTo("NSE_FNO")))
                .withRequestBody(matchingJsonPath("$.securityId", equalTo("52175"))).withRequestBody(matchingJsonPath("$.triggerPrice", equalTo("24900.0"))));
    }

    @Test
    void ordersTradesPositionsAndHoldingsMapToHejjeTypes() {
        adapter.restoreSession(TOKEN);
        wiremock.stubFor(get(urlEqualTo("/v2/orders")).willReturn(json(200, "[" + ORDER_OPEN + ","
                + ORDER_OPEN.replace("112111182198", "2").replace("\"PENDING\"", "\"TRADED\"").replace("\"filledQty\":0", "\"filledQty\":10")
                        .replace("\"averageTradedPrice\":0", "\"averageTradedPrice\":1500.25").replace("\"remainingQuantity\":10", "\"remainingQuantity\":0") + ","
                + ORDER_OPEN.replace("112111182198", "3").replace("\"PENDING\"", "\"REJECTED\"").replace("\"omsErrorDescription\":\"\"", "\"omsErrorDescription\":\"RMS: margin\"") + ","
                + ORDER_OPEN.replace("112111182198", "4").replace("\"LIMIT\"", "\"STOP_LOSS_MARKET\"") + ","
                + ORDER_OPEN.replace("112111182198", "5").replace("\"PENDING\"", "\"PART_TRADED\"").replace("\"filledQty\":0", "\"filledQty\":4")
                        .replace("\"remainingQuantity\":10", "\"remainingQuantity\":6") + "]")));
        List<BrokerOrder> orders = adapter.getOrders();
        assertThat(orders).extracting(BrokerOrder::status).containsExactly(BrokerOrderStatus.OPEN, BrokerOrderStatus.COMPLETE, BrokerOrderStatus.REJECTED,
                BrokerOrderStatus.TRIGGER_PENDING, BrokerOrderStatus.OPEN);
        BrokerOrder open = orders.get(0);
        assertThat(open.instrumentId()).isEqualTo(INFY);
        assertThat(open.tag()).isEqualTo("abc123");
        assertThat(open.limitPrice()).isEqualByComparingTo("1500.50");
        assertThat(open.product()).isEqualTo(Product.MIS);
        assertThat(open.placedAt()).isEqualTo(Instant.parse("2026-09-08T04:00:00Z")); // IST
        assertThat(orders.get(1).averagePrice()).isEqualByComparingTo("1500.25");
        assertThat(orders.get(1).pendingQuantity()).isZero();
        assertThat(orders.get(2).statusMessage()).isEqualTo("RMS: margin");
        assertThat(orders.get(4).filledQuantity()).isEqualTo(4);
        assertThat(orders.get(4).pendingQuantity()).isEqualTo(6);

        wiremock.stubFor(get(urlEqualTo("/v2/trades")).willReturn(json(200, """
                [{"dhanClientId":"1100003626","orderId":"2","exchangeOrderId":"X2","exchangeTradeId":"T9","transactionType":"BUY","exchangeSegment":"NSE_EQ",
                  "productType":"INTRADAY","orderType":"LIMIT","tradingSymbol":"INFY","securityId":"1594","tradedQuantity":10,"tradedPrice":1500.25,
                  "createTime":"2026-09-08 09:31:00","updateTime":"2026-09-08 09:31:00","exchangeTime":"2026-09-08 09:31:00"}]""")));
        assertThat(adapter.getTrades()).singleElement().satisfies(t -> {
            assertThat(t.brokerTradeId()).isEqualTo("T9");
            assertThat(t.instrumentId()).isEqualTo(INFY);
            assertThat(t.price()).isEqualByComparingTo("1500.25");
        });

        wiremock.stubFor(get(urlEqualTo("/v2/positions")).willReturn(json(200, """
                [{"dhanClientId":"1100003626","tradingSymbol":"NIFTY-Sep2026-FUT","securityId":"52175","positionType":"SHORT","exchangeSegment":"NSE_FNO",
                  "productType":"MARGIN","buyAvg":0,"buyQty":0,"costPrice":24950.0,"sellAvg":24950.5,"sellQty":75,"netQty":-75,"realizedProfit":0.0,
                  "unrealizedProfit":-1200.5,"dayBuyQty":0,"daySellQty":75,"dayBuyValue":0,"daySellValue":1871287.5,"drvExpiryDate":"2026-09-29"}]""")));
        BrokerPosition p = adapter.getPositions().get(0);
        assertThat(p.instrumentId()).isEqualTo(NIFTY_FUT);
        assertThat(p.netQuantity()).isEqualTo(-75);
        assertThat(p.averagePrice()).isEqualByComparingTo("24950.50");
        assertThat(p.product()).isEqualTo(Product.NRML);
        assertThat(p.unrealizedPnl().toRupees()).isEqualByComparingTo("-1200.50");

        wiremock.stubFor(get(urlEqualTo("/v2/holdings")).willReturn(json(200, """
                [{"exchange":"ALL","tradingSymbol":"INFY","securityId":"1594","isin":"INE009A01021","totalQty":5,"dpQty":5,"t1Qty":0,"availableQty":5,
                  "collateralQty":0,"avgCostPrice":1400.0}]""")));
        assertThat(adapter.getHoldings()).singleElement().satisfies(h -> {
            assertThat(h.instrumentId()).isEqualTo(INFY);
            assertThat(h.quantity()).isEqualTo(5);
            assertThat(h.isin()).isEqualTo("INE009A01021");
        });
    }

    @Test
    void quotesAndCandlesUseSegmentsSecurityIdsAndIst() {
        adapter.restoreSession(TOKEN);
        wiremock.stubFor(post(urlEqualTo("/v2/marketfeed/quote")).withRequestBody(equalToJson("{\"NSE_EQ\":[1594]}")).willReturn(json(200, """
                {"data":{"NSE_EQ":{"1594":{"average_price":1501.1,"buy_quantity":100,"sell_quantity":90,"depth":{"buy":[{"quantity":10,"orders":2,"price":1500.4}],
                 "sell":[{"quantity":8,"orders":1,"price":1500.6}]},"last_price":1500.5,"last_quantity":5,"last_trade_time":"08/09/2026 10:00:00",
                 "lower_circuit_limit":1350.0,"upper_circuit_limit":1650.0,"net_change":2.5,"ohlc":{"open":1495.0,"close":1498.0,"high":1505.0,"low":1490.0},
                 "oi":0,"volume":123456}}},"status":"success"}""")));
        Quote q = adapter.getQuote(Set.of(INFY)).get(0);
        assertThat(q.instrumentId()).isEqualTo(INFY);
        assertThat(q.lastPrice()).isEqualByComparingTo("1500.50");
        assertThat(q.bid()).isEqualByComparingTo("1500.40");
        assertThat(q.ask()).isEqualByComparingTo("1500.60");
        assertThat(q.volume()).isEqualTo(123456);
        assertThat(q.ts()).isEqualTo(Instant.parse("2026-09-08T04:30:00Z"));
        wiremock.verify(postRequestedFor(urlEqualTo("/v2/marketfeed/quote")).withHeader("client-id", equalTo(CLIENT)));

        wiremock.stubFor(post(urlEqualTo("/v2/charts/intraday")).willReturn(json(200, """
                {"open":[24900.0,24910.0],"high":[24920.0,24930.0],"low":[24890.0,24905.0],"close":[24910.0,24925.0],"volume":[1500,1200],
                 "timestamp":[1788839100,1788839400],"open_interest":[1000,1010]}""")));
        List<BrokerCandle> candles = adapter.getHistory(NIFTY_FUT, Timeframe.M5, Instant.parse("2026-09-08T03:45:00Z"), Instant.parse("2026-09-08T04:00:00Z"));
        assertThat(candles).hasSize(2);
        assertThat(candles.get(0).openTime()).isEqualTo(Instant.parse("2026-09-08T03:45:00Z")); // 09:15 IST
        assertThat(candles.get(1).oi()).isEqualTo(1010);
        wiremock.verify(postRequestedFor(urlEqualTo("/v2/charts/intraday")).withRequestBody(equalToJson("""
                {"securityId":"52175","exchangeSegment":"NSE_FNO","instrument":"FUTIDX","oi":true,"interval":"5",
                 "fromDate":"2026-09-08 09:15:00","toDate":"2026-09-08 09:30:00"}""")));

        wiremock.stubFor(post(urlEqualTo("/v2/charts/historical")).willReturn(json(200,
                "{\"open\":[1490.0],\"high\":[1510.0],\"low\":[1480.0],\"close\":[1500.0],\"volume\":[900000],\"timestamp\":[1788805800]}")));
        assertThat(adapter.getHistory(INFY, Timeframe.D1, Instant.parse("2026-09-07T18:30:00Z"), Instant.parse("2026-09-08T18:29:59Z"))).hasSize(1);
        wiremock.verify(postRequestedFor(urlEqualTo("/v2/charts/historical")).withRequestBody(matchingJsonPath("$.toDate", equalTo("2026-09-09")))
                .withRequestBody(matchingJsonPath("$.instrument", equalTo("EQUITY"))));
        assertThatThrownBy(() -> adapter.getHistory(INFY, Timeframe.M3, Instant.parse("2026-09-08T03:45:00Z"), Instant.parse("2026-09-08T04:00:00Z")))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.INPUT));
    }

    record Case(int http, String code, BrokerException.Kind kind) {}

    @Test
    void everyErrorFamilyMapsToAKindAndAnInvalidTokenDropsTheSession() {
        List<Case> cases = List.of(new Case(429, "DH-904", BrokerException.Kind.RATE_LIMIT), new Case(400, "DH-905", BrokerException.Kind.INPUT),
                new Case(400, "DH-906", BrokerException.Kind.REJECTED), new Case(400, "DH-903", BrokerException.Kind.REJECTED),
                new Case(500, "DH-908", BrokerException.Kind.NETWORK), new Case(502, "", BrokerException.Kind.NETWORK),
                new Case(429, "", BrokerException.Kind.RATE_LIMIT), new Case(403, "DH-902", BrokerException.Kind.AUTH),
                new Case(401, "DH-901", BrokerException.Kind.AUTH));
        for (Case c : cases) {
            adapter.restoreSession(TOKEN);
            wiremock.stubFor(post(urlEqualTo("/v2/orders")).willReturn(c.code().isEmpty() ? aResponse().withStatus(c.http()).withBody("<html>bad gateway</html>")
                    : json(c.http(), error(c.code(), "Type", "msg for " + c.code()))));
            assertThatThrownBy(() -> adapter.placeOrder(limitBuy(INFY, 1, "1500.00"))).as("%s", c)
                    .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(c.kind()));
        }
        assertThat(adapter.sessionState()).isEqualTo(BrokerSessionState.DISCONNECTED); // DH-901 was last
        assertThat(published).singleElement().isInstanceOfSatisfying(BrokerAuthRejected.class, e -> assertThat(e.broker()).isEqualTo("dhan"));
        assertThatThrownBy(adapter::getFunds).isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.AUTH));
    }

    @Test
    void timeoutsAndUnmappedInstrumentsAreClassified() {
        adapter.restoreSession(TOKEN);
        wiremock.stubFor(get(urlEqualTo("/v2/positions")).willReturn(json(200, "[]").withFixedDelay(3000)));
        assertThatThrownBy(adapter::getPositions).isInstanceOfSatisfying(BrokerException.class, e -> {
            assertThat(e.kind()).isEqualTo(BrokerException.Kind.TIMEOUT);
            assertThat(e.outcomeUnknown()).isTrue();
        });
        assertThatThrownBy(() -> adapter.placeOrder(limitBuy(UUID.randomUUID(), 1, "10.00")))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.INPUT));
        wiremock.verify(0, postRequestedFor(urlPathEqualTo("/v2/orders")));
    }

    @Test
    void instrumentsComeFromThePublicSecurityMaster() {
        wiremock.stubFor(get(urlEqualTo("/scrip.csv")).willReturn(aResponse().withStatus(200).withBody(DhanInstrumentCsvTest.CSV)));
        List<BrokerInstrument> rows = adapter.getInstruments();
        assertThat(rows).extracting(BrokerInstrument::brokerToken).contains("NSE_EQ:1594", "NSE_FNO:52175");
        wiremock.verify(getRequestedFor(urlEqualTo("/scrip.csv")).withoutHeader("access-token"));
    }
}
