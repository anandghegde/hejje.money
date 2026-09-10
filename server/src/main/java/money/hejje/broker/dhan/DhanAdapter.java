package money.hejje.broker.dhan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerCandle;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerHolding;
import money.hejje.broker.BrokerInstrument;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerModifyRequest;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.broker.BrokerPosition;
import money.hejje.broker.BrokerProfile;
import money.hejje.broker.BrokerSession;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.BrokerTrade;
import money.hejje.broker.Funds;
import money.hejje.broker.MarketDataListener;
import money.hejje.broker.MarketDataStream;
import money.hejje.broker.OrderMargin;
import money.hejje.broker.Quote;
import money.hejje.broker.internal.BrokerAuthRejected;
import money.hejje.common.InstrumentType;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Timeframe;
import money.hejje.common.Validity;
import money.hejje.common.time.HejjeClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * DhanHQ v2 adapter (plan M5.6, docs/broker-dhan.md) over the documented REST API with the JDK HTTP client. The
 * access token lives only in memory (and encrypted in {@code broker_session}); it is sent in the {@code access-token}
 * header and never logged or put in an error message. Orders carry Hejje's tag as {@code correlationId}. Order updates
 * come from the order poller and reconciliation (no postback/websocket consumer); market data is polled
 * ({@link DhanQuoteStream}).
 */
@Component
@ConditionalOnProperty(name = "hejje.broker.adapter", havingValue = "dhan")
public class DhanAdapter implements BrokerAdapter {

    public static final String BROKER_CODE = "dhan";
    private static final int MAX_INTRADAY_DAYS = 90;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> INDEX_UNDERLYINGS = Set.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50", "SENSEX", "BANKEX");

    private final DhanProperties properties;
    private final BrokerInstrumentResolver instruments;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    private volatile String accessToken;
    private volatile String clientId;
    private volatile BrokerSessionState state = BrokerSessionState.DISCONNECTED;

    @Autowired
    public DhanAdapter(DhanProperties properties, BrokerInstrumentResolver instruments, HejjeClock clock, ApplicationEventPublisher events) {
        if (properties.clientId() == null || properties.clientId().isBlank()) {
            throw new IllegalStateException("hejje.broker.dhan.client-id (HEJJE_DHAN_CLIENT_ID) is required for the dhan adapter");
        }
        this.properties = properties;
        this.instruments = instruments;
        this.clock = clock;
        this.events = events;
        this.clientId = properties.clientId();
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    @Override
    public String brokerCode() {
        return BROKER_CODE;
    }

    // ---- session ----

    /** With an API key: a fresh consent and its login page; without one, web.dhan.co, where a 24 h token is generated to paste. */
    @Override
    public String loginUrl() {
        if (!properties.consentConfigured()) {
            return "https://web.dhan.co";
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.authUrl() + "/app/generate-consent?client_id=" + encode(properties.clientId())))
                .timeout(properties.readTimeout()).header("app_id", properties.appId()).header("app_secret", properties.appSecret())
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        JsonNode r = send(request, false);
        String consent = r.path("consentAppId").asText("");
        if (consent.isBlank()) {
            throw new BrokerException(BrokerException.Kind.UNKNOWN, "Dhan returned no consentAppId");
        }
        return properties.authUrl() + "/login/consentApp-login?consentAppId=" + encode(consent);
    }

    /**
     * The consent login's {@code tokenId} (exchanged with the API key) or, without an API key, an access token pasted
     * from web.dhan.co (a JWT; checked with {@code GET /profile}).
     */
    @Override
    public BrokerSession authenticate(String requestToken) {
        if (requestToken == null || requestToken.isBlank()) {
            throw new BrokerException(BrokerException.Kind.INPUT, "no token");
        }
        if (requestToken.startsWith("eyJ") && requestToken.chars().filter(c -> c == '.').count() == 2) {
            install(requestToken.trim(), properties.clientId());
            JsonNode profile = call("GET", "/profile", null);
            install(requestToken.trim(), profile.path("dhanClientId").asText(properties.clientId()));
            return new BrokerSession(clientId, accessToken, null, clock.now());
        }
        if (!properties.consentConfigured()) {
            throw new BrokerException(BrokerException.Kind.INPUT, "Dhan consent login needs hejje.broker.dhan.app-id and app-secret; or paste an access token");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.authUrl() + "/app/consumeApp-consent?tokenId=" + encode(requestToken)))
                .timeout(properties.readTimeout()).header("app_id", properties.appId()).header("app_secret", properties.appSecret()).GET().build();
        JsonNode r = send(request, false);
        String token = r.path("accessToken").asText("");
        if (token.isBlank()) {
            throw new BrokerException(BrokerException.Kind.AUTH, "Dhan returned no access token");
        }
        install(token, r.path("dhanClientId").asText(properties.clientId()));
        return new BrokerSession(clientId, token, null, clock.now());
    }

    private void install(String token, String client) {
        accessToken = token;
        clientId = client;
        state = BrokerSessionState.CONNECTED;
    }

    @Override
    public void restoreSession(String token) {
        install(token, properties.clientId());
    }

    @Override
    public void clearSession() {
        accessToken = null;
        state = BrokerSessionState.DISCONNECTED;
    }

    /** Dhan documents no logout endpoint; the token expires on its own after 24 h. */
    @Override
    public void logout() {
        clearSession();
    }

    @Override
    public BrokerSessionState sessionState() {
        return state;
    }

    @Override
    public BrokerProfile getProfile() {
        JsonNode p = call("GET", "/profile", null);
        List<String> exchanges = new ArrayList<>();
        String segments = p.path("activeSegment").asText("");
        if (segments.contains("Equity")) {
            exchanges.addAll(List.of("NSE", "BSE"));
        }
        if (segments.contains("Derivative")) {
            exchanges.addAll(List.of("NFO", "BFO"));
        }
        if (segments.contains("Commodity")) {
            exchanges.add("MCX");
        }
        String id = p.path("dhanClientId").asText(clientId);
        return new BrokerProfile(id, id, null, exchanges, List.of("CNC", "MIS", "NRML"));
    }

    // ---- market data ----

    @Override
    public List<Quote> getQuote(Set<UUID> instrumentIds) {
        Map<String, UUID> byKey = new LinkedHashMap<>();
        ObjectNode body = json.createObjectNode();
        for (UUID id : instrumentIds) {
            Optional<BrokerInstrumentRef> ref = instruments.forInstrument(id, BROKER_CODE);
            if (ref.isEmpty()) {
                continue;
            }
            String segment = ref.get().exchangeSegment();
            String security = securityId(ref.get());
            ArrayNode list = body.has(segment) ? (ArrayNode) body.get(segment) : body.putArray(segment);
            list.add(Long.parseLong(security));
            byKey.put(segment + ":" + security, id);
        }
        if (byKey.isEmpty()) {
            return List.of();
        }
        JsonNode data = call("POST", "/marketfeed/quote", body).path("data");
        List<Quote> out = new ArrayList<>();
        data.fields().forEachRemaining(seg -> seg.getValue().fields().forEachRemaining(q -> {
            UUID id = byKey.get(seg.getKey() + ":" + q.getKey());
            if (id == null) {
                return;
            }
            JsonNode v = q.getValue();
            JsonNode ohlc = v.path("ohlc");
            Instant ts = DhanMapper.quoteTime(v.path("last_trade_time").asText(null), clock.zone());
            out.add(new Quote(id, ts == null ? clock.now() : ts, DhanMapper.decimal(v, "last_price"), DhanMapper.decimal(v.path("depth").path("buy").path(0), "price"),
                    DhanMapper.decimal(v.path("depth").path("sell").path(0), "price"), v.path("volume").asLong(0), v.path("oi").asLong(0),
                    DhanMapper.decimal(ohlc, "open"), DhanMapper.decimal(ohlc, "high"), DhanMapper.decimal(ohlc, "low"), DhanMapper.decimal(ohlc, "close")));
        }));
        return out;
    }

    /** Daily candles from {@code /charts/historical} (toDate exclusive), intraday 1/5/15/25/60-minute candles from {@code /charts/intraday} in 90-day windows. */
    @Override
    public List<BrokerCandle> getHistory(UUID instrumentId, Timeframe timeframe, Instant from, Instant to) {
        BrokerInstrumentRef ref = ref(instrumentId);
        boolean derivative = ref.type() == InstrumentType.FUT || ref.type() == InstrumentType.OPT;
        List<BrokerCandle> out = new ArrayList<>();
        if (!timeframe.isIntraday()) {
            ObjectNode body = chartBody(ref, derivative);
            body.put("expiryCode", 0);
            body.put("fromDate", DAY.format(from.atZone(clock.zone()).toLocalDate()));
            body.put("toDate", DAY.format(to.atZone(clock.zone()).toLocalDate().plusDays(1)));
            candles(call("POST", "/charts/historical", body), out);
        } else {
            long minutes = timeframe.duration().toMinutes();
            if (!Set.of(1L, 5L, 15L, 25L, 60L).contains(minutes)) {
                throw new BrokerException(BrokerException.Kind.INPUT, "Dhan has no " + minutes + "-minute candles (1, 5, 15, 25, 60)");
            }
            Instant start = from;
            while (!start.isAfter(to)) {
                Instant end = start.plus(Duration.ofDays(MAX_INTRADAY_DAYS)).isBefore(to) ? start.plus(Duration.ofDays(MAX_INTRADAY_DAYS)) : to;
                ObjectNode body = chartBody(ref, derivative);
                body.put("interval", String.valueOf(minutes));
                body.put("fromDate", MINUTE.format(start.atZone(clock.zone())));
                body.put("toDate", MINUTE.format(end.atZone(clock.zone())));
                candles(call("POST", "/charts/intraday", body), out);
                start = end.plusSeconds(1);
            }
        }
        return out.stream().filter(c -> !c.openTime().isBefore(from) && !c.openTime().isAfter(to)).distinct().toList();
    }

    private ObjectNode chartBody(BrokerInstrumentRef ref, boolean derivative) {
        ObjectNode body = json.createObjectNode();
        body.put("securityId", securityId(ref));
        body.put("exchangeSegment", ref.exchangeSegment());
        body.put("instrument", chartInstrument(ref));
        body.put("oi", derivative);
        return body;
    }

    static String chartInstrument(BrokerInstrumentRef ref) {
        boolean index = INDEX_UNDERLYINGS.stream().anyMatch(u -> ref.tradingSymbol().toUpperCase().startsWith(u));
        return switch (ref.type()) {
            case INDEX -> "INDEX";
            case EQ -> "EQUITY";
            case FUT -> index ? "FUTIDX" : "FUTSTK";
            case OPT -> index ? "OPTIDX" : "OPTSTK";
        };
    }

    private static void candles(JsonNode r, List<BrokerCandle> out) {
        JsonNode ts = r.path("timestamp");
        for (int i = 0; i < ts.size(); i++) {
            out.add(new BrokerCandle(Instant.ofEpochSecond(ts.get(i).asLong()), at(r, "open", i), at(r, "high", i), at(r, "low", i), at(r, "close", i),
                    r.path("volume").path(i).asLong(0), r.path("open_interest").path(i).asLong(0)));
        }
    }

    private static BigDecimal at(JsonNode r, String field, int i) {
        return new BigDecimal(r.path(field).path(i).asText("0")).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public MarketDataStream streamMarketData(MarketDataListener listener) {
        if (state != BrokerSessionState.CONNECTED) {
            throw new BrokerException(BrokerException.Kind.AUTH, "no Dhan session");
        }
        return new DhanQuoteStream(this, listener, properties.quotePollInterval());
    }

    /** The compact security master (public, no token). */
    @Override
    public List<BrokerInstrument> getInstruments() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.instrumentsUrl())).timeout(Duration.ofMinutes(2)).GET().build();
        try {
            HttpResponse<InputStream> r = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() / 100 != 2) {
                    throw new BrokerException(BrokerException.Kind.NETWORK, "Dhan security master HTTP " + r.statusCode());
                }
                return DhanInstrumentCsv.parse(in);
            }
        } catch (HttpTimeoutException e) {
            throw new BrokerException(BrokerException.Kind.TIMEOUT, "Dhan security master timed out");
        } catch (IOException e) {
            throw new BrokerException(BrokerException.Kind.NETWORK, "Dhan security master unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerException(BrokerException.Kind.NETWORK, "interrupted");
        }
    }

    // ---- orders ----

    @Override
    public BrokerOrderRef placeOrder(BrokerOrderRequest request) {
        BrokerInstrumentRef ref = ref(request.instrumentId());
        ObjectNode body = json.createObjectNode();
        body.put("dhanClientId", clientId);
        if (request.tag() != null) {
            body.put("correlationId", request.tag());
        }
        body.put("transactionType", request.side().name());
        body.put("exchangeSegment", ref.exchangeSegment());
        body.put("productType", DhanMapper.product(request.product()));
        body.put("orderType", DhanMapper.orderType(request.orderType()));
        body.put("validity", (request.validity() == null ? Validity.DAY : request.validity()).name());
        body.put("securityId", securityId(ref));
        body.put("quantity", request.quantity().value());
        body.put("disclosedQuantity", 0);
        body.put("price", request.limitPrice() == null ? BigDecimal.ZERO : request.limitPrice().value());
        if (request.triggerPrice() != null) {
            body.put("triggerPrice", request.triggerPrice().value());
        }
        body.put("afterMarketOrder", false);
        String id = call("POST", "/orders", body).path("orderId").asText("");
        if (id.isBlank()) {
            throw new BrokerException(BrokerException.Kind.UNKNOWN, "Dhan returned no orderId");
        }
        return new BrokerOrderRef(id);
    }

    /** Dhan needs the full order on a modify: unchanged fields are taken from the current order. */
    @Override
    public BrokerOrderRef modifyOrder(BrokerOrderRef ref, BrokerModifyRequest request) {
        BrokerOrder current = getOrder(ref);
        OrderType type = request.orderType() != null ? request.orderType() : current.orderType();
        ObjectNode body = json.createObjectNode();
        body.put("dhanClientId", clientId);
        body.put("orderId", ref.brokerOrderId());
        body.put("orderType", DhanMapper.orderType(type));
        body.put("quantity", request.quantity() != null ? request.quantity().value() : current.quantity());
        BigDecimal price = request.limitPrice() != null ? request.limitPrice().value() : current.limitPrice();
        body.put("price", price == null ? BigDecimal.ZERO : price);
        BigDecimal trigger = request.triggerPrice() != null ? request.triggerPrice().value() : current.triggerPrice();
        if (trigger != null) {
            body.put("triggerPrice", trigger);
        }
        body.put("disclosedQuantity", 0);
        Validity validity = request.validity() != null ? request.validity() : current.validity();
        body.put("validity", (validity == null ? Validity.DAY : validity).name());
        String id = call("PUT", "/orders/" + encode(ref.brokerOrderId()), body).path("orderId").asText(ref.brokerOrderId());
        return new BrokerOrderRef(id.isBlank() ? ref.brokerOrderId() : id);
    }

    @Override
    public BrokerOrderRef cancelOrder(BrokerOrderRef ref) {
        call("DELETE", "/orders/" + encode(ref.brokerOrderId()), null);
        return ref;
    }

    @Override
    public BrokerOrder getOrder(BrokerOrderRef ref) {
        JsonNode r = call("GET", "/orders/" + encode(ref.brokerOrderId()), null);
        JsonNode o = r.isArray() ? r.path(0) : r;
        if (o.isMissingNode() || o.path("orderId").asText("").isBlank()) {
            throw new BrokerException(BrokerException.Kind.INPUT, "Dhan has no order " + ref.brokerOrderId());
        }
        return order(o);
    }

    @Override
    public List<BrokerOrder> getOrders() {
        List<BrokerOrder> out = new ArrayList<>();
        call("GET", "/orders", null).forEach(o -> out.add(order(o)));
        return out;
    }

    private BrokerOrder order(JsonNode o) {
        OrderType type = DhanMapper.orderType(o.path("orderType").asText());
        BrokerOrderStatus status = DhanMapper.status(o.path("orderStatus").asText(), type);
        int quantity = o.path("quantity").asInt(0);
        int filled = o.has("filledQty") ? o.path("filledQty").asInt(0) : o.path("filled_qty").asInt(0);
        int remaining = o.has("remainingQuantity") ? o.path("remainingQuantity").asInt(0) : Math.max(0, quantity - filled);
        Instant placed = DhanMapper.time(o.path("createTime").asText(null), clock.zone());
        Instant updated = DhanMapper.time(o.path("updateTime").asText(null), clock.zone());
        String segment = o.path("exchangeSegment").asText("");
        return new BrokerOrder(o.path("orderId").asText(), instrumentId(segment, o.path("securityId").asText("")), o.path("tradingSymbol").asText(""), segment,
                DhanMapper.side(o.path("transactionType").asText()), quantity, filled, status.isTerminal() ? 0 : remaining,
                DhanMapper.decimal(o, "averageTradedPrice"), type, DhanMapper.product(o.path("productType").asText()),
                type == OrderType.LIMIT || type == OrderType.SL ? DhanMapper.positiveOrNull(o, "price") : null, DhanMapper.positiveOrNull(o, "triggerPrice"),
                DhanMapper.validity(o.path("validity").asText()), status, o.path("orderStatus").asText(""), DhanMapper.textOrNull(o, "omsErrorDescription"),
                DhanMapper.textOrNull(o, "correlationId"), null, placed, updated != null ? updated : placed, DhanMapper.raw(o, json));
    }

    @Override
    public List<BrokerTrade> getTrades() {
        List<BrokerTrade> out = new ArrayList<>();
        for (JsonNode t : call("GET", "/trades", null)) {
            String segment = t.path("exchangeSegment").asText("");
            Instant ts = DhanMapper.time(t.path("exchangeTime").asText(null), clock.zone());
            out.add(new BrokerTrade(t.path("exchangeTradeId").asText(t.path("orderId").asText()), t.path("orderId").asText(),
                    instrumentId(segment, t.path("securityId").asText("")), t.path("tradingSymbol").asText(""), segment,
                    DhanMapper.side(t.path("transactionType").asText()), DhanMapper.product(t.path("productType").asText()), t.path("tradedQuantity").asInt(0),
                    DhanMapper.decimal(t, "tradedPrice"), ts != null ? ts : DhanMapper.time(t.path("createTime").asText(null), clock.zone()), DhanMapper.raw(t, json)));
        }
        return out;
    }

    @Override
    public List<BrokerPosition> getPositions() {
        List<BrokerPosition> out = new ArrayList<>();
        for (JsonNode p : call("GET", "/positions", null)) {
            String segment = p.path("exchangeSegment").asText("");
            int net = p.path("netQty").asInt(0);
            BigDecimal average = DhanMapper.decimal(p, net < 0 ? "sellAvg" : "buyAvg");
            out.add(new BrokerPosition(instrumentId(segment, p.path("securityId").asText("")), p.path("tradingSymbol").asText(""), segment,
                    DhanMapper.product(p.path("productType").asText()), net, average == null ? BigDecimal.ZERO.setScale(2) : average, p.path("dayBuyQty").asInt(0),
                    p.path("daySellQty").asInt(0), orZero(DhanMapper.decimal(p, "dayBuyValue")), orZero(DhanMapper.decimal(p, "daySellValue")),
                    DhanMapper.money(p, "realizedProfit"), DhanMapper.money(p, "unrealizedProfit"), null, DhanMapper.raw(p, json)));
        }
        return out;
    }

    @Override
    public List<BrokerHolding> getHoldings() {
        List<BrokerHolding> out = new ArrayList<>();
        for (JsonNode h : call("GET", "/holdings", null)) {
            String security = h.path("securityId").asText("");
            UUID id = instrumentId("NSE_EQ", security);
            String segment = id != null ? "NSE_EQ" : "BSE_EQ";
            out.add(new BrokerHolding(id != null ? id : instrumentId("BSE_EQ", security), h.path("tradingSymbol").asText(""), segment,
                    DhanMapper.textOrNull(h, "isin"), h.path("totalQty").asInt(0), orZero(DhanMapper.decimal(h, "avgCostPrice")), null, DhanMapper.raw(h, json)));
        }
        return out;
    }

    /** {@code availabelBalance} is Dhan's documented spelling. */
    @Override
    public Funds getFunds() {
        JsonNode f = call("GET", "/fundlimit", null);
        Money available = DhanMapper.money(f, "availabelBalance");
        return new Funds(available, DhanMapper.money(f, "utilizedAmount"), available, DhanMapper.money(f, "sodLimit"), DhanMapper.raw(f, json));
    }

    /** One {@code /margincalculator} call per order (the multi-order endpoint's documentation contradicts itself), so no hedge benefit. */
    @Override
    public List<OrderMargin> getOrderMargins(List<BrokerOrderRequest> requests) {
        List<OrderMargin> out = new ArrayList<>();
        for (BrokerOrderRequest request : requests) {
            BrokerInstrumentRef ref = ref(request.instrumentId());
            ObjectNode body = json.createObjectNode();
            body.put("dhanClientId", clientId);
            body.put("exchangeSegment", ref.exchangeSegment());
            body.put("transactionType", request.side().name());
            body.put("quantity", request.quantity().value());
            body.put("productType", DhanMapper.product(request.product()));
            body.put("securityId", securityId(ref));
            body.put("price", request.limitPrice() != null ? request.limitPrice().value()
                    : request.triggerPrice() != null ? request.triggerPrice().value() : BigDecimal.ZERO);
            if (request.triggerPrice() != null) {
                body.put("triggerPrice", request.triggerPrice().value());
            }
            JsonNode m = call("POST", "/margincalculator", body);
            Money zero = Money.of(BigDecimal.ZERO.setScale(2));
            out.add(new OrderMargin(request.instrumentId(), DhanMapper.money(m, "totalMargin"), DhanMapper.money(m, "spanMargin"),
                    DhanMapper.money(m, "exposureMargin"), zero, DhanMapper.money(m, "variableMargin"), DhanMapper.money(m, "brokerage")));
        }
        return out;
    }

    // ---- plumbing ----

    private BrokerInstrumentRef ref(UUID instrumentId) {
        return instruments.forInstrument(instrumentId, BROKER_CODE)
                .orElseThrow(() -> new BrokerException(BrokerException.Kind.INPUT, "instrument " + instrumentId + " has no Dhan mapping"));
    }

    static String securityId(BrokerInstrumentRef ref) {
        String token = ref.brokerToken();
        int colon = token.indexOf(':');
        return colon >= 0 ? token.substring(colon + 1) : token;
    }

    private UUID instrumentId(String segment, String securityId) {
        if (segment.isBlank() || securityId.isBlank()) {
            return null;
        }
        return instruments.byBrokerToken(BROKER_CODE, segment + ":" + securityId).orElse(null);
    }

    private JsonNode call(String method, String path, Object body) {
        String token = accessToken;
        if (token == null) {
            throw new BrokerException(BrokerException.Kind.AUTH, "no Dhan session");
        }
        HttpRequest.BodyPublisher publisher;
        try {
            publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        } catch (IOException e) {
            throw new BrokerException(BrokerException.Kind.INPUT, "unserializable request");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + path)).timeout(properties.readTimeout())
                .header("Content-Type", "application/json").header("Accept", "application/json").header("access-token", token)
                .header("client-id", clientId).method(method, publisher).build();
        return send(request, true);
    }

    private JsonNode send(HttpRequest request, boolean session) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new BrokerException(BrokerException.Kind.TIMEOUT, "Dhan timed out");
        } catch (IOException e) {
            throw new BrokerException(BrokerException.Kind.NETWORK, "Dhan unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerException(BrokerException.Kind.NETWORK, "interrupted");
        }
        if (response.statusCode() / 100 != 2) {
            DhanMapper.DhanError error = DhanMapper.error(response.body(), json);
            String message = error.describe(response.statusCode());
            if (session && error.dropsSession()) {
                authRejected(message);
            }
            throw new BrokerException(DhanMapper.kind(error, response.statusCode()), message);
        }
        String text = response.body();
        if (text == null || text.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(text);
        } catch (IOException e) {
            throw new BrokerException(BrokerException.Kind.UNKNOWN, "Dhan answered with non-JSON");
        }
    }

    private void authRejected(String detail) {
        accessToken = null;
        state = BrokerSessionState.DISCONNECTED;
        events.publishEvent(new BrokerAuthRejected(BROKER_CODE, detail));
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    LocalDate today() {
        return clock.today();
    }
}
