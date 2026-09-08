package money.hejje.broker.zerodha;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Margin;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.Position;
import com.zerodhatech.models.Profile;
import com.zerodhatech.models.Trade;
import com.zerodhatech.models.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import money.hejje.broker.BrokerOrderUpdate;
import money.hejje.broker.BrokerOrderUpdates;
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
import money.hejje.common.Money;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * {@link BrokerAdapter} for Zerodha Kite Connect, wrapping the official {@code javakiteconnect} client. Everything
 * Kite-specific (field names, enums, error families, IST timestamps, the 20-character tag) stays inside this package.
 * The api secret and access token are held in memory only and never logged.
 */
@Component
@ConditionalOnProperty(name = "hejje.broker.adapter", havingValue = "zerodha")
public class ZerodhaKiteAdapter implements BrokerAdapter {

    public static final String BROKER_CODE = "zerodha";
    private static final Logger log = LoggerFactory.getLogger(ZerodhaKiteAdapter.class);

    @FunctionalInterface
    interface KiteCall<T> {
        T call() throws KiteException, java.io.IOException;
    }

    private final KiteConnect kite;
    private final KiteProperties properties;
    private final BrokerInstrumentResolver instruments;
    private final BrokerOrderUpdates orderUpdates;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;
    private final Map<String, UUID> symbolCache = new ConcurrentHashMap<>();
    private volatile BrokerSessionState state = BrokerSessionState.DISCONNECTED;
    private volatile String brokerUserId;
    private volatile String accessToken;

    @Autowired
    ZerodhaKiteAdapter(KiteProperties properties, BrokerInstrumentResolver instruments, BrokerOrderUpdates orderUpdates,
            HejjeClock clock, ApplicationEventPublisher events) {
        this(KiteClientFactory.create(requireConfigured(properties)), properties, instruments, orderUpdates, clock, events);
    }

    ZerodhaKiteAdapter(KiteConnect kite, KiteProperties properties, BrokerInstrumentResolver instruments, BrokerOrderUpdates orderUpdates,
            HejjeClock clock, ApplicationEventPublisher events) {
        this.kite = kite;
        this.properties = properties;
        this.instruments = instruments;
        this.orderUpdates = orderUpdates;
        this.clock = clock;
        this.events = events;
        this.kite.setSessionExpiryHook(() -> authRejected("Kite reported the session expired"));
    }

    private static KiteProperties requireConfigured(KiteProperties properties) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("hejje.broker.adapter=zerodha needs HEJJE_KITE_API_KEY and HEJJE_KITE_API_SECRET");
        }
        return properties;
    }

    @Override
    public String brokerCode() {
        return BROKER_CODE;
    }

    // --- session ---------------------------------------------------------------------------------------------------

    @Override
    public String loginUrl() {
        return kite.getLoginURL();
    }

    @Override
    public BrokerSession authenticate(String requestToken) {
        User user = call("authenticate", () -> kite.generateSession(requestToken, properties.apiSecret()));
        install(user.accessToken, user.publicToken, user.userId);
        Instant establishedAt = user.loginTime != null ? KiteMapper.instant(user.loginTime) : clock.now();
        log.info("Kite session established for user {}", user.userId);
        return new BrokerSession(user.userId, user.accessToken, user.publicToken, establishedAt);
    }

    @Override
    public void restoreSession(String accessToken) {
        install(accessToken, null, brokerUserId);
    }

    private void install(String token, String publicToken, String userId) {
        this.accessToken = token;
        this.brokerUserId = userId;
        kite.setAccessToken(token);
        if (publicToken != null) {
            kite.setPublicToken(publicToken);
        }
        if (userId != null) {
            kite.setUserId(userId);
        }
        state = BrokerSessionState.CONNECTED;
    }

    @Override
    public void clearSession() {
        accessToken = null;
        kite.setAccessToken("");
        if (state == BrokerSessionState.CONNECTED) {
            state = BrokerSessionState.DISCONNECTED;
        }
    }

    @Override
    public void logout() {
        try {
            if (accessToken != null) {
                call("logout", kite::invalidateAccessToken);
            }
        } finally {
            clearSession();
        }
    }

    @Override
    public BrokerSessionState sessionState() {
        return state;
    }

    @Override
    public BrokerProfile getProfile() {
        Profile p = call("getProfile", kite::getProfile);
        return new BrokerProfile(brokerUserId, p.userName, p.email,
                p.exchanges == null ? List.of() : Arrays.asList(p.exchanges), p.products == null ? List.of() : Arrays.asList(p.products));
    }

    // --- market data --------------------------------------------------------------------------------------------------

    @Override
    public List<Quote> getQuote(Set<UUID> instrumentIds) {
        Map<String, UUID> keys = new LinkedHashMap<>();
        for (UUID id : instrumentIds) {
            BrokerInstrumentRef ref = ref(id);
            keys.put(ref.exchangeSegment() + ":" + ref.tradingSymbol(), id);
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<String, com.zerodhatech.models.Quote> quotes = call("getQuote", () -> kite.getQuote(keys.keySet().toArray(String[]::new)));
        List<Quote> out = new ArrayList<>();
        Instant now = clock.now();
        quotes.forEach((key, q) -> {
            UUID id = keys.get(key);
            if (id == null) {
                return;
            }
            BigDecimal bid = null;
            BigDecimal ask = null;
            if (q.depth != null) {
                if (q.depth.buy != null && !q.depth.buy.isEmpty() && q.depth.buy.get(0).getPrice() > 0) {
                    bid = KiteMapper.decimal(q.depth.buy.get(0).getPrice());
                }
                if (q.depth.sell != null && !q.depth.sell.isEmpty() && q.depth.sell.get(0).getPrice() > 0) {
                    ask = KiteMapper.decimal(q.depth.sell.get(0).getPrice());
                }
            }
            Instant ts = q.timestamp != null ? KiteMapper.instant(q.timestamp) : now;
            out.add(new Quote(id, ts, KiteMapper.decimal(q.lastPrice), bid, ask, (long) q.volumeTradedToday, (long) q.oi,
                    q.ohlc == null ? null : KiteMapper.decimal(q.ohlc.open), q.ohlc == null ? null : KiteMapper.decimal(q.ohlc.high),
                    q.ohlc == null ? null : KiteMapper.decimal(q.ohlc.low), q.ohlc == null ? null : KiteMapper.decimal(q.ohlc.close)));
        });
        return out;
    }

    @Override
    public List<BrokerCandle> getHistory(UUID instrumentId, Timeframe timeframe, Instant from, Instant to) {
        BrokerInstrumentRef ref = ref(instrumentId);
        boolean oi = ref.type() == money.hejje.common.InstrumentType.FUT || ref.type() == money.hejje.common.InstrumentType.OPT;
        HistoricalData data = call("getHistory", () -> kite.getHistoricalData(KiteMapper.kiteDate(from), KiteMapper.kiteDate(to),
                ref.brokerToken(), KiteMapper.interval(timeframe), false, oi));
        List<BrokerCandle> out = new ArrayList<>(data.dataArrayList.size());
        for (HistoricalData c : data.dataArrayList) {
            out.add(new BrokerCandle(KiteMapper.candleInstant(c.timeStamp), KiteMapper.decimal(c.open), KiteMapper.decimal(c.high),
                    KiteMapper.decimal(c.low), KiteMapper.decimal(c.close), c.volume, c.oi));
        }
        return out;
    }

    @Override
    public MarketDataStream streamMarketData(MarketDataListener listener) {
        String token = accessToken;
        if (token == null || state != BrokerSessionState.CONNECTED) {
            throw new BrokerException(BrokerException.Kind.AUTH, "no broker session; login first");
        }
        return new KiteTickerStream(token, properties.apiKey(), instruments, orderUpdates, listener, clock);
    }

    @Override
    public List<BrokerInstrument> getInstruments() {
        List<com.zerodhatech.models.Instrument> rows = call("getInstruments", kite::getInstruments);
        List<BrokerInstrument> out = new ArrayList<>(rows.size());
        for (com.zerodhatech.models.Instrument i : rows) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("instrument_token", Long.toString(i.instrument_token));
            row.put("exchange_token", Long.toString(i.exchange_token));
            row.put("tradingsymbol", i.tradingsymbol);
            row.put("name", i.name);
            row.put("last_price", Double.toString(i.last_price));
            row.put("expiry", i.expiry == null ? "" : KiteMapper.localDate(i.expiry).toString());
            row.put("strike", i.strike);
            row.put("tick_size", BigDecimal.valueOf(i.tick_size).toPlainString());
            row.put("lot_size", Integer.toString(i.lot_size));
            row.put("instrument_type", i.instrument_type);
            row.put("segment", i.segment);
            row.put("exchange", i.exchange);
            KiteInstrumentCsv.toInstrument(row).ifPresent(out::add);
        }
        return out;
    }

    // --- transactional ------------------------------------------------------------------------------------------------

    @Override
    public BrokerOrderRef placeOrder(BrokerOrderRequest request) {
        BrokerInstrumentRef ref = ref(request.instrumentId());
        OrderParams params = KiteMapper.orderParams(request, ref);
        OrderResponse response = call("placeOrder", () -> kite.placeOrder(params, Constants.VARIETY_REGULAR));
        if (response == null || response.orderId == null) {
            throw new BrokerException(BrokerException.Kind.UNKNOWN, "broker returned no order id", false, null);
        }
        return new BrokerOrderRef(response.orderId);
    }

    @Override
    public BrokerOrderRef modifyOrder(BrokerOrderRef ref, BrokerModifyRequest request) {
        OrderParams params = KiteMapper.modifyParams(request);
        Order order = call("modifyOrder", () -> kite.modifyOrder(ref.brokerOrderId(), params, Constants.VARIETY_REGULAR));
        return new BrokerOrderRef(order.orderId != null ? order.orderId : ref.brokerOrderId());
    }

    @Override
    public BrokerOrderRef cancelOrder(BrokerOrderRef ref) {
        Order order = call("cancelOrder", () -> kite.cancelOrder(ref.brokerOrderId(), Constants.VARIETY_REGULAR));
        return new BrokerOrderRef(order.orderId != null ? order.orderId : ref.brokerOrderId());
    }

    // --- reads ---------------------------------------------------------------------------------------------------------

    @Override
    public BrokerOrder getOrder(BrokerOrderRef ref) {
        List<Order> history = call("getOrder", () -> kite.getOrderHistory(ref.brokerOrderId()));
        if (history.isEmpty()) {
            throw new BrokerException(BrokerException.Kind.INPUT, "no such order " + ref.brokerOrderId(), false, null);
        }
        return toBrokerOrder(history.get(history.size() - 1));
    }

    @Override
    public List<BrokerOrder> getOrders() {
        return call("getOrders", kite::getOrders).stream().map(this::toBrokerOrder).toList();
    }

    @Override
    public List<BrokerTrade> getTrades() {
        List<Trade> trades = call("getTrades", kite::getTrades);
        return trades.stream().map(t -> KiteMapper.trade(t, instrumentId(t.exchange, t.tradingSymbol))).toList();
    }

    @Override
    public List<BrokerPosition> getPositions() {
        Map<String, List<Position>> positions = call("getPositions", kite::getPositions);
        List<Position> net = positions.getOrDefault("net", List.of());
        return net.stream().map(p -> KiteMapper.position(p, instrumentId(p.exchange, p.tradingSymbol))).toList();
    }

    @Override
    public List<BrokerHolding> getHoldings() {
        return call("getHoldings", kite::getHoldings).stream()
                .map(h -> new BrokerHolding(instrumentId(h.exchange, h.tradingSymbol), h.tradingSymbol, h.exchange, h.isin, h.quantity,
                        KiteMapper.decimal(h.averagePrice), KiteMapper.decimal(h.lastPrice), Map.of("product", String.valueOf(h.product))))
                .toList();
    }

    @Override
    public Funds getFunds() {
        Map<String, Margin> margins = call("getFunds", kite::getMargins);
        Margin equity = margins.get(Constants.MARGIN_EQUITY);
        if (equity == null) {
            throw new BrokerException(BrokerException.Kind.UNKNOWN, "no equity margin segment in response", false, null);
        }
        Money cash = equity.available != null ? KiteMapper.money(equity.available.cash) : Money.ZERO;
        Money live = equity.available != null && equity.available.liveBalance != null ? KiteMapper.money(equity.available.liveBalance) : cash;
        Money debits = equity.utilised != null ? KiteMapper.money(equity.utilised.debits) : Money.ZERO;
        Map<String, Object> raw = new LinkedHashMap<>();
        if (equity.utilised != null) {
            raw.put("span", equity.utilised.span);
            raw.put("exposure", equity.utilised.exposure);
            raw.put("option_premium", equity.utilised.optionPremium);
            raw.put("m2m_unrealised", equity.utilised.m2mUnrealised);
            raw.put("m2m_realised", equity.utilised.m2mRealised);
        }
        return new Funds(live, debits, KiteMapper.money(equity.net), null, raw);
    }

    @Override
    public List<OrderMargin> getOrderMargins(List<BrokerOrderRequest> requests) {
        List<MarginCalculationParams> params = new ArrayList<>();
        for (BrokerOrderRequest r : requests) {
            BrokerInstrumentRef ref = ref(r.instrumentId());
            OrderParams op = KiteMapper.orderParams(r, ref);
            MarginCalculationParams m = new MarginCalculationParams();
            m.tradingSymbol = op.tradingsymbol;
            m.exchange = op.exchange;
            m.transactionType = op.transactionType;
            m.variety = Constants.VARIETY_REGULAR;
            m.product = op.product;
            m.orderType = op.orderType;
            m.quantity = op.quantity;
            m.price = op.price == null ? 0 : op.price;
            m.triggerPrice = op.triggerPrice == null ? 0 : op.triggerPrice;
            params.add(m);
        }
        List<MarginCalculationData> data = call("getOrderMargins", () -> kite.getMarginCalculation(params));
        List<OrderMargin> out = new ArrayList<>();
        for (int i = 0; i < data.size() && i < requests.size(); i++) {
            MarginCalculationData d = data.get(i);
            out.add(new OrderMargin(requests.get(i).instrumentId(), KiteMapper.money(d.total), KiteMapper.money(d.span),
                    KiteMapper.money(d.exposure), KiteMapper.money(d.option_premium), KiteMapper.money(d.additional),
                    d.charges == null ? Money.ZERO : KiteMapper.money(d.charges.total)));
        }
        return out;
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    /** Converts a Kite order (REST or postback) to a {@link BrokerOrder}, resolving the Hejje instrument. */
    public BrokerOrder toBrokerOrder(Order order) {
        return KiteMapper.order(order, o -> instrumentId(o.exchange, o.tradingSymbol));
    }

    /** Entry point for the postback controller. */
    void publishOrderUpdate(Order order, BrokerOrderUpdate.Source source) {
        orderUpdates.publish(new BrokerOrderUpdate(toBrokerOrder(order), source, clock.now()));
    }

    private UUID instrumentId(String exchange, String tradingSymbol) {
        if (exchange == null || tradingSymbol == null) {
            return null;
        }
        String key = exchange + ":" + tradingSymbol;
        UUID cached = symbolCache.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<UUID> id = instruments.byTradingSymbol(BROKER_CODE, exchange, tradingSymbol);
        id.ifPresent(v -> symbolCache.put(key, v));
        return id.orElse(null);
    }

    private BrokerInstrumentRef ref(UUID instrumentId) {
        return instruments.forInstrument(instrumentId, BROKER_CODE)
                .orElseThrow(() -> new BrokerException(BrokerException.Kind.INPUT, "instrument " + instrumentId + " has no " + BROKER_CODE + " mapping", false, null));
    }

    private <T> T call(String op, KiteCall<T> body) {
        try {
            return body.call();
        } catch (Throwable t) {
            BrokerException e = KiteMapper.toBrokerException(t);
            if (e.kind() == BrokerException.Kind.AUTH) {
                authRejected(op + ": " + e.brokerMessage());
            }
            log.warn("Kite {} failed: {} {}", op, e.kind(), e.brokerMessage());
            throw e;
        }
    }

    private void authRejected(String detail) {
        if (state == BrokerSessionState.CONNECTED) {
            state = BrokerSessionState.DISCONNECTED;
            events.publishEvent(new BrokerAuthRejected(BROKER_CODE, detail));
        }
    }
}
