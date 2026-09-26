package money.hejje.broker.zerodha;

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
import java.time.Duration;
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
import money.hejje.broker.BrokerOrderUpdates;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The contract against a WireMock Kite Connect (response shapes as in {@link ZerodhaKiteAdapterWireMockTest}). */
class ZerodhaContractTest extends BrokerAdapterContractTest {

    static final BrokerInstrumentResolver RESOLVER = new BrokerInstrumentResolver() {
        final BrokerInstrumentRef infy = new BrokerInstrumentRef(INSTRUMENT, Exchange.NSE, InstrumentType.EQ, "408065", "INFY", "NSE", 1, new BigDecimal("0.05"));

        @Override
        public Optional<BrokerInstrumentRef> forInstrument(UUID instrumentId, String broker) {
            return INSTRUMENT.equals(instrumentId) ? Optional.of(infy) : Optional.empty();
        }

        @Override
        public Optional<UUID> byBrokerToken(String broker, String brokerToken) {
            return "408065".equals(brokerToken) ? Optional.of(INSTRUMENT) : Optional.empty();
        }

        @Override
        public Optional<UUID> byTradingSymbol(String broker, String exchangeSegment, String tradingSymbol) {
            return "NSE".equals(exchangeSegment) && "INFY".equals(tradingSymbol) ? Optional.of(INSTRUMENT) : Optional.empty();
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
        KiteClientFactory.applyBaseUrl(KiteClientFactory.DEFAULT_BASE_URL);
    }

    static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    @Override
    protected BrokerAdapter connect() {
        wiremock.resetAll();
        KiteProperties props = new KiteProperties("contract-key", "contract-secret", wiremock.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2));
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);
        ZerodhaKiteAdapter kite = new ZerodhaKiteAdapter(KiteClientFactory.create(props), props, RESOLVER, new BrokerOrderUpdates(), clock, e -> { });
        wiremock.stubFor(post(urlEqualTo("/session/token")).willReturn(json(200, """
                {"status":"success","data":{"user_id":"AB1234","user_name":"Test","email":"t@example.com","broker":"ZERODHA","user_type":"individual",
                 "access_token":"contract-access","public_token":"pub","login_time":"2026-09-08 08:30:00","products":["MIS"],"exchanges":["NSE"],
                 "order_types":["LIMIT"],"api_key":"contract-key"}}""")));
        kite.authenticate("req-contract");
        return kite;
    }

    /** Kite's GTT API: its mapping is tested against WireMock in ZerodhaKiteAdapterWireMockTest. */
    @Override
    protected GttSupport gttSupport() {
        return GttSupport.BROKER;
    }

    @Override
    protected void givenQuote(String lastPrice) {
        wiremock.stubFor(get(urlPathEqualTo("/quote")).willReturn(json(200, """
                {"status":"success","data":{"NSE:INFY":{"instrument_token":408065,"timestamp":"2026-09-08 10:00:00","last_trade_time":"2026-09-08 09:59:59",
                 "last_price":%s,"volume":1000,"oi":0,"ohlc":{"open":1490,"high":1500,"low":1485,"close":1488},
                 "depth":{"buy":[{"price":1499.9,"quantity":1,"orders":1}],"sell":[{"price":1500.1,"quantity":1,"orders":1}]}}}}""".formatted(lastPrice))));
    }

    @Override
    protected void givenPlaceAccepted(String brokerOrderId) {
        wiremock.stubFor(post(urlPathEqualTo("/orders/regular")).willReturn(json(200, "{\"status\":\"success\",\"data\":{\"order_id\":\"" + brokerOrderId + "\"}}")));
    }

    static String order(String id, String status) {
        return """
                {"order_id":"%s","exchange":"NSE","tradingsymbol":"INFY","transaction_type":"BUY","quantity":1,"filled_quantity":0,"pending_quantity":%d,
                 "average_price":0,"order_type":"LIMIT","product":"MIS","price":1400.0,"trigger_price":0,"validity":"DAY","status":"%s",
                 "status_message":null,"tag":"ct1","order_timestamp":"2026-09-08 10:00:00","exchange_update_timestamp":"2026-09-08 10:00:01","variety":"regular"}"""
                .formatted(id, "OPEN".equals(status) ? 1 : 0, status);
    }

    @Override
    protected void givenOrder(String brokerOrderId, BrokerOrderStatus status) {
        String s = status == BrokerOrderStatus.CANCELLED ? "CANCELLED" : "OPEN";
        wiremock.stubFor(get(urlEqualTo("/orders/" + brokerOrderId)).willReturn(json(200, "{\"status\":\"success\",\"data\":[" + order(brokerOrderId, s) + "]}")));
        wiremock.stubFor(get(urlEqualTo("/orders")).willReturn(json(200, "{\"status\":\"success\",\"data\":[" + order(brokerOrderId, s) + "]}")));
    }

    @Override
    protected void givenCancelAccepted(String brokerOrderId) {
        wiremock.stubFor(delete(urlPathEqualTo("/orders/regular/" + brokerOrderId))
                .willReturn(json(200, "{\"status\":\"success\",\"data\":{\"order_id\":\"" + brokerOrderId + "\"}}")));
    }

    @Override
    protected void givenEmptyAccount() {
        wiremock.stubFor(get(urlEqualTo("/portfolio/positions")).willReturn(json(200, "{\"status\":\"success\",\"data\":{\"net\":[],\"day\":[]}}")));
        wiremock.stubFor(get(urlEqualTo("/portfolio/holdings")).willReturn(json(200, "{\"status\":\"success\",\"data\":[]}")));
        wiremock.stubFor(get(urlEqualTo("/trades")).willReturn(json(200, "{\"status\":\"success\",\"data\":[]}")));
        wiremock.stubFor(get(urlEqualTo("/user/margins")).willReturn(json(200, """
                {"status":"success","data":{"equity":{"enabled":true,"net":1000.0,"available":{"adhoc_margin":0,"cash":1000.0,"opening_balance":1000.0,
                 "live_balance":1000.0,"collateral":0,"intraday_payin":0},"utilised":{"debits":0,"exposure":0,"m2m_realised":0,"m2m_unrealised":0,
                 "option_premium":0,"payout":0,"span":0,"holding_sales":0,"turnover":0}},"commodity":{"enabled":false}}}""")));
    }

    @Override
    protected Set<BrokerException.Kind> scriptedErrors() {
        return EnumSet.of(BrokerException.Kind.AUTH, BrokerException.Kind.RATE_LIMIT, BrokerException.Kind.INPUT, BrokerException.Kind.REJECTED,
                BrokerException.Kind.NETWORK);
    }

    @Override
    protected void givenNextPlaceFails(BrokerException.Kind kind) {
        int http = switch (kind) {
            case AUTH -> 403;
            case RATE_LIMIT -> 429;
            case NETWORK -> 502;
            default -> 400;
        };
        String type = switch (kind) {
            case AUTH -> "TokenException";
            case RATE_LIMIT, NETWORK -> "NetworkException";
            case REJECTED -> "OrderException";
            default -> "InputException";
        };
        wiremock.stubFor(post(urlPathEqualTo("/orders/regular"))
                .willReturn(json(http, "{\"status\":\"error\",\"message\":\"scripted " + kind + "\",\"error_type\":\"" + type + "\"}")));
    }

    @Override
    protected void assertNoOrderSent() {
        wiremock.verify(0, postRequestedFor(urlPathEqualTo("/orders/regular")));
    }
}
