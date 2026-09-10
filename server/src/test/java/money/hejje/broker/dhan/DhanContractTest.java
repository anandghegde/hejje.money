package money.hejje.broker.dhan;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerAdapterContractTest;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The contract against a WireMock DhanHQ v2. */
class DhanContractTest extends BrokerAdapterContractTest {

    static final BrokerInstrumentResolver RESOLVER = new BrokerInstrumentResolver() {
        final BrokerInstrumentRef infy = new BrokerInstrumentRef(INSTRUMENT, Exchange.NSE, InstrumentType.EQ, "NSE_EQ:1594", "INFY", "NSE_EQ", 1,
                new BigDecimal("0.05"));

        @Override
        public Optional<BrokerInstrumentRef> forInstrument(UUID instrumentId, String broker) {
            return INSTRUMENT.equals(instrumentId) ? Optional.of(infy) : Optional.empty();
        }

        @Override
        public Optional<UUID> byBrokerToken(String broker, String brokerToken) {
            return "NSE_EQ:1594".equals(brokerToken) ? Optional.of(INSTRUMENT) : Optional.empty();
        }

        @Override
        public Optional<UUID> byTradingSymbol(String broker, String exchangeSegment, String tradingSymbol) {
            return Optional.empty();
        }
    };

    static WireMockServer wiremock;

    @BeforeAll
    static void start() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stop() {
        wiremock.stop();
    }

    static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    @Override
    protected BrokerAdapter connect() {
        wiremock.resetAll();
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);
        DhanAdapter dhan = new DhanAdapter(DhanAdapterWireMockTest.props(wiremock.baseUrl()), RESOLVER, clock, e -> { });
        dhan.restoreSession(DhanAdapterWireMockTest.TOKEN);
        return dhan;
    }

    @Override
    protected void givenQuote(String lastPrice) {
        wiremock.stubFor(post(urlEqualTo("/v2/marketfeed/quote")).willReturn(json(200, """
                {"data":{"NSE_EQ":{"1594":{"last_price":%s,"last_trade_time":"08/09/2026 09:59:59","volume":1000,"oi":0,
                 "ohlc":{"open":1490,"close":1488,"high":1500,"low":1485},"depth":{"buy":[{"quantity":1,"orders":1,"price":1499.9}],
                 "sell":[{"quantity":1,"orders":1,"price":1500.1}]}}}},"status":"success"}""".formatted(lastPrice))));
    }

    @Override
    protected void givenPlaceAccepted(String brokerOrderId) {
        wiremock.stubFor(post(urlEqualTo("/v2/orders")).willReturn(json(200, "{\"orderId\":\"" + brokerOrderId + "\",\"orderStatus\":\"PENDING\"}")));
    }

    static String order(String id, String status) {
        return """
                {"dhanClientId":"1100003626","orderId":"%s","correlationId":"ct1","orderStatus":"%s","transactionType":"BUY","exchangeSegment":"NSE_EQ",
                 "productType":"INTRADAY","orderType":"LIMIT","validity":"DAY","tradingSymbol":"INFY","securityId":"1594","quantity":1,"disclosedQuantity":0,
                 "price":1400.0,"triggerPrice":0,"afterMarketOrder":false,"createTime":"2026-09-08 10:00:00","updateTime":"2026-09-08 10:00:01",
                 "omsErrorCode":"","omsErrorDescription":"","remainingQuantity":1,"averageTradedPrice":0,"filledQty":0}""".formatted(id, status);
    }

    @Override
    protected void givenOrder(String brokerOrderId, BrokerOrderStatus status) {
        String s = status == BrokerOrderStatus.CANCELLED ? "CANCELLED" : "PENDING";
        wiremock.stubFor(get(urlEqualTo("/v2/orders/" + brokerOrderId)).willReturn(json(200, order(brokerOrderId, s))));
        wiremock.stubFor(get(urlEqualTo("/v2/orders")).willReturn(json(200, "[" + order(brokerOrderId, s) + "]")));
    }

    @Override
    protected void givenCancelAccepted(String brokerOrderId) {
        wiremock.stubFor(delete(urlEqualTo("/v2/orders/" + brokerOrderId)).willReturn(json(200, "{\"orderId\":\"" + brokerOrderId + "\",\"orderStatus\":\"CANCELLED\"}")));
    }

    @Override
    protected void givenEmptyAccount() {
        wiremock.stubFor(get(urlEqualTo("/v2/positions")).willReturn(json(200, "[]")));
        wiremock.stubFor(get(urlEqualTo("/v2/holdings")).willReturn(json(200, "[]")));
        wiremock.stubFor(get(urlEqualTo("/v2/trades")).willReturn(json(200, "[]")));
        wiremock.stubFor(get(urlEqualTo("/v2/fundlimit")).willReturn(json(200, """
                {"dhanClientId":"1100003626","availabelBalance":1000.0,"sodLimit":1000.0,"collateralAmount":0.0,"receiveableAmount":0.0,
                 "utilizedAmount":0.0,"blockedPayoutAmount":0.0,"withdrawableBalance":1000.0}""")));
    }

    @Override
    protected Set<BrokerException.Kind> scriptedErrors() {
        return EnumSet.of(BrokerException.Kind.AUTH, BrokerException.Kind.RATE_LIMIT, BrokerException.Kind.INPUT, BrokerException.Kind.REJECTED,
                BrokerException.Kind.NETWORK);
    }

    @Override
    protected void givenNextPlaceFails(BrokerException.Kind kind) {
        String code = switch (kind) {
            case AUTH -> "DH-901";
            case RATE_LIMIT -> "DH-904";
            case REJECTED -> "DH-906";
            case NETWORK -> "DH-908";
            default -> "DH-905";
        };
        int http = kind == BrokerException.Kind.AUTH ? 401 : kind == BrokerException.Kind.RATE_LIMIT ? 429 : kind == BrokerException.Kind.NETWORK ? 500 : 400;
        wiremock.stubFor(post(urlEqualTo("/v2/orders"))
                .willReturn(json(http, "{\"errorType\":\"Scripted\",\"errorCode\":\"" + code + "\",\"errorMessage\":\"scripted " + kind + "\"}")));
    }

    @Override
    protected void assertNoOrderSent() {
        wiremock.verify(0, postRequestedFor(urlPathEqualTo("/v2/orders")));
    }
}
