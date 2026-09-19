package money.hejje.broker.fake;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
import money.hejje.broker.zerodha.KiteInstrumentCsv;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.Validity;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-memory broker for dev and test ({@code hejje.broker.adapter=fake}, the default outside prod).
 * <ul>
 *   <li>Instrument master from a checked-in Kite CSV fixture.</li>
 *   <li>Quotes are injected ({@link #injectQuote}); MARKET orders fill at the current quote, LIMIT/SL orders when a
 *       quote crosses. Fills update an in-memory orderbook, tradebook, positions and funds.</li>
 *   <li>Order updates go through {@link BrokerOrderUpdates} on a single background thread, like a real ticker.
 *       Tests call {@link #flush()} to wait for them.</li>
 *   <li>Scripted failures: {@link #failNext}, {@link #delayNext}, {@link #dropAck}, {@link #simulateSessionExpiry}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "hejje.broker.adapter", havingValue = "fake", matchIfMissing = true)
public class FakeBrokerAdapter implements BrokerAdapter {

    public static final String BROKER_CODE = "fake";
    static final String FIXTURE = "broker/fake/kite-instruments-fixture.csv";
    private static final Logger log = LoggerFactory.getLogger(FakeBrokerAdapter.class);
    private static final BigDecimal MARGIN_RATE = new BigDecimal("0.20");

    private final BrokerOrderUpdates updates;
    private final BrokerInstrumentResolver instruments;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;
    private final ExecutorService updateThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fake-broker-updates");
        t.setDaemon(true);
        return t;
    });

    private final Map<UUID, Quote> quotes = new ConcurrentHashMap<>();
    private final Map<String, FakeOrder> orders = new LinkedHashMap<>();
    private final List<BrokerTrade> trades = new ArrayList<>();
    private final Map<String, FakePosition> positions = new LinkedHashMap<>();
    private final Map<String, List<BrokerCandle>> history = new ConcurrentHashMap<>();
    private final List<FakeStream> streams = new CopyOnWriteArrayList<>();
    private final Deque<BrokerException.Kind> failNext = new ArrayDeque<>();
    /**
     * Seeded per process from the wall-clock time of day so ids never collide with rows a previous run left in the database.
     * Deliberately not the Hejje clock: a SIM instance restarts at the same simulated times, and broker order ids are an id
     * namespace, never part of a result (plan M7.1 keeps them out of the determinism hash).
     */
    private final AtomicLong sequence = new AtomicLong((System.currentTimeMillis() / 1000 % 86400) * 100000 + 1);
    private final AtomicBoolean dropAck = new AtomicBoolean();
    private volatile long delayNextMs;
    private volatile boolean connected;
    private volatile boolean brokerSideValid = true;
    private final Money startingCapital;
    private Money cash;

    @Autowired
    FakeBrokerAdapter(FakeBrokerProperties properties, BrokerOrderUpdates updates, BrokerInstrumentResolver instruments, HejjeClock clock,
            ApplicationEventPublisher events) {
        this.updates = updates;
        this.instruments = instruments;
        this.clock = clock;
        this.events = events;
        this.connected = properties.connected();
        this.startingCapital = Money.ofRupees(properties.startingCapital());
        this.cash = startingCapital;
    }

    // --- records ---------------------------------------------------------------------------------------------------

    private static final class FakeOrder {
        String id;
        UUID instrumentId;
        String tradingSymbol;
        String exchangeSegment;
        Side side;
        int quantity;
        int filled;
        BigDecimal avgPrice = BigDecimal.ZERO.setScale(2);
        OrderType type;
        Product product;
        BigDecimal limit;
        BigDecimal trigger;
        Validity validity;
        BrokerOrderStatus status;
        String message = "";
        String tag;
        Instant placedAt;
        Instant updatedAt;
        boolean triggered;

        int pending() {
            return quantity - filled;
        }
    }

    private static final class FakePosition {
        UUID instrumentId;
        String tradingSymbol;
        String exchangeSegment;
        Product product;
        int net;
        BigDecimal avg = BigDecimal.ZERO.setScale(2);
        Money realized = Money.ZERO;
        int dayBuy;
        int daySell;
        BigDecimal dayBuyValue = BigDecimal.ZERO;
        BigDecimal daySellValue = BigDecimal.ZERO;
    }

    // --- BrokerAdapter: identity and session -----------------------------------------------------------------------

    @Override
    public String brokerCode() {
        return BROKER_CODE;
    }

    @Override
    public String loginUrl() {
        return "http://localhost/fake-broker/login?api_key=fake&v=3";
    }

    @Override
    public synchronized BrokerSession authenticate(String requestToken) {
        if (requestToken == null || requestToken.isBlank() || "bad".equals(requestToken)) {
            throw new BrokerException(BrokerException.Kind.AUTH, "invalid request token", false, null);
        }
        connected = true;
        brokerSideValid = true;
        return new BrokerSession("FAKE001", "fake-access-token-" + UUID.randomUUID(), "fake-public-token", clock.now());
    }

    @Override
    public synchronized void restoreSession(String accessToken) {
        connected = true;
        brokerSideValid = true;
    }

    @Override
    public synchronized void clearSession() {
        connected = false;
    }

    @Override
    public synchronized void logout() {
        connected = false;
    }

    @Override
    public BrokerSessionState sessionState() {
        return connected ? BrokerSessionState.CONNECTED : BrokerSessionState.DISCONNECTED;
    }

    @Override
    public BrokerProfile getProfile() {
        requireSession("getProfile");
        return new BrokerProfile("FAKE001", "Fake Trader", "fake@hejje.money", List.of("NSE", "NFO", "BSE"), List.of("MIS", "CNC", "NRML"));
    }

    // --- market data -------------------------------------------------------------------------------------------------

    @Override
    public List<Quote> getQuote(Set<UUID> instrumentIds) {
        requireSession("getQuote");
        List<Quote> out = new ArrayList<>();
        for (UUID id : instrumentIds) {
            Quote q = quotes.get(id);
            if (q != null) {
                out.add(q);
            }
        }
        return out;
    }

    @Override
    public List<BrokerCandle> getHistory(UUID instrumentId, Timeframe timeframe, Instant from, Instant to) {
        requireSession("getHistory");
        return history.getOrDefault(instrumentId + "/" + timeframe, List.of()).stream()
                .filter(c -> !c.openTime().isBefore(from) && !c.openTime().isAfter(to)).toList();
    }

    @Override
    public MarketDataStream streamMarketData(MarketDataListener listener) {
        requireSession("streamMarketData");
        FakeStream stream = new FakeStream(listener);
        streams.add(stream);
        listener.onConnected();
        return stream;
    }

    @Override
    public List<BrokerInstrument> getInstruments() {
        try (var reader = new InputStreamReader(new ClassPathResource(FIXTURE).getInputStream(), StandardCharsets.UTF_8)) {
            return KiteInstrumentCsv.parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- transactional -----------------------------------------------------------------------------------------------

    @Override
    public BrokerOrderRef placeOrder(BrokerOrderRequest request) {
        requireSession("placeOrder");
        script();
        if (instruments.forInstrument(request.instrumentId(), BROKER_CODE).isEmpty()) { // like a real broker (adapter contract, M5.6)
            throw new BrokerException(BrokerException.Kind.INPUT, "instrument " + request.instrumentId() + " has no fake mapping");
        }
        boolean dropThisAck = dropAck.getAndSet(false);
        FakeOrder order;
        synchronized (this) {
            order = new FakeOrder();
            order.id = nextId();
            order.instrumentId = request.instrumentId();
            Optional<BrokerInstrumentRef> ref = instruments.forInstrument(request.instrumentId(), BROKER_CODE);
            order.tradingSymbol = ref.map(BrokerInstrumentRef::tradingSymbol).orElse(request.instrumentId().toString());
            order.exchangeSegment = ref.map(BrokerInstrumentRef::exchangeSegment).orElse("NSE");
            order.side = request.side();
            order.quantity = request.quantity().value();
            order.type = request.orderType();
            order.product = request.product();
            order.limit = request.limitPrice() == null ? null : request.limitPrice().value();
            order.trigger = request.triggerPrice() == null ? null : request.triggerPrice().value();
            order.validity = request.validity();
            order.tag = request.tag();
            order.placedAt = clock.now();
            order.updatedAt = order.placedAt;
            order.status = order.type == OrderType.SL || order.type == OrderType.SL_M ? BrokerOrderStatus.TRIGGER_PENDING : BrokerOrderStatus.OPEN;
            orders.put(order.id, order);
            if (!dropThisAck) {
                publish(order);
                match(order, quotes.get(order.instrumentId));
            }
        }
        if (dropThisAck) {
            // the order reached the broker but neither the ack nor any update comes back; the poll fallback must find it
            throw new BrokerException(BrokerException.Kind.TIMEOUT, "simulated: acknowledgement lost", true, null);
        }
        return new BrokerOrderRef(order.id);
    }

    @Override
    public BrokerOrderRef modifyOrder(BrokerOrderRef ref, BrokerModifyRequest request) {
        requireSession("modifyOrder");
        script();
        synchronized (this) {
            FakeOrder order = open(ref);
            if (request.quantity() != null) {
                if (request.quantity().value() < order.filled) {
                    throw new BrokerException(BrokerException.Kind.REJECTED, "quantity below filled quantity", false, null);
                }
                order.quantity = request.quantity().value();
            }
            if (request.orderType() != null) {
                order.type = request.orderType();
            }
            if (request.limitPrice() != null) {
                order.limit = request.limitPrice().value();
            }
            if (request.triggerPrice() != null) {
                order.trigger = request.triggerPrice().value();
            }
            if (request.validity() != null) {
                order.validity = request.validity();
            }
            order.updatedAt = clock.now();
            publish(order);
            match(order, quotes.get(order.instrumentId));
            return new BrokerOrderRef(order.id);
        }
    }

    @Override
    public BrokerOrderRef cancelOrder(BrokerOrderRef ref) {
        requireSession("cancelOrder");
        script();
        synchronized (this) {
            FakeOrder order = open(ref);
            order.status = BrokerOrderStatus.CANCELLED;
            order.message = "cancelled by user";
            order.updatedAt = clock.now();
            publish(order);
            return new BrokerOrderRef(order.id);
        }
    }

    // --- reads -----------------------------------------------------------------------------------------------------------

    @Override
    public synchronized BrokerOrder getOrder(BrokerOrderRef ref) {
        requireSession("getOrder");
        FakeOrder order = orders.get(ref.brokerOrderId());
        if (order == null) {
            throw new BrokerException(BrokerException.Kind.INPUT, "no such order " + ref.brokerOrderId(), false, null);
        }
        return toBrokerOrder(order);
    }

    @Override
    public synchronized List<BrokerOrder> getOrders() {
        requireSession("getOrders");
        return orders.values().stream().map(this::toBrokerOrder).toList();
    }

    @Override
    public synchronized List<BrokerTrade> getTrades() {
        requireSession("getTrades");
        return List.copyOf(trades);
    }

    @Override
    public synchronized List<BrokerPosition> getPositions() {
        requireSession("getPositions");
        return positions.values().stream().map(this::toBrokerPosition).toList();
    }

    @Override
    public List<BrokerHolding> getHoldings() {
        requireSession("getHoldings");
        return List.of();
    }

    @Override
    public synchronized Funds getFunds() {
        requireSession("getFunds");
        Money used = Money.ZERO;
        for (FakePosition p : positions.values()) {
            if (p.net != 0) {
                used = used.plus(marginFor(p.product, p.avg, Math.abs(p.net)));
            }
        }
        Money available = cash.minus(used);
        return new Funds(available, used, available, startingCapital, Map.of("simulated", true));
    }

    @Override
    public List<OrderMargin> getOrderMargins(List<BrokerOrderRequest> requests) {
        requireSession("getOrderMargins");
        List<OrderMargin> out = new ArrayList<>();
        for (BrokerOrderRequest r : requests) {
            BigDecimal price = r.limitPrice() != null ? r.limitPrice().value()
                    : Optional.ofNullable(quotes.get(r.instrumentId())).map(Quote::lastPrice).orElse(BigDecimal.ZERO);
            Money total = marginFor(r.product(), price, r.quantity().value());
            out.add(new OrderMargin(r.instrumentId(), total, Money.ZERO, total, Money.ZERO, Money.ZERO, Money.ZERO));
        }
        return out;
    }

    // --- scripting and seeding (tests, dev tooling) ----------------------------------------------------------------------

    /** Sets the current price of an instrument, emits a tick to streams and matches resting orders. */
    public void injectQuote(UUID instrumentId, BigDecimal price) {
        injectTick(new MarketTick(instrumentId, clock.now(), price.setScale(2, RoundingMode.HALF_UP),
                price.subtract(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP),
                price.add(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP), 0, 0, MarketTick.Mode.QUOTE));
    }

    public void injectQuote(UUID instrumentId, String price) {
        injectQuote(instrumentId, new BigDecimal(price));
    }

    /** Feeds a full tick: updates the quote, emits to streams, matches resting orders. */
    public void injectTick(MarketTick tick) {
        Quote quote = new Quote(tick.instrumentId(), tick.ts(), tick.lastPrice(), tick.bid(), tick.ask(), tick.volume(), tick.oi(),
                null, null, null, null);
        quotes.put(tick.instrumentId(), quote);
        for (FakeStream stream : streams) {
            stream.emit(tick);
        }
        synchronized (this) {
            for (FakeOrder order : List.copyOf(orders.values())) {
                if (order.instrumentId.equals(tick.instrumentId()) && !order.status.isTerminal()) {
                    match(order, quote);
                }
            }
        }
    }

    /** The next transactional call fails with the given kind. */
    public void failNext(BrokerException.Kind kind) {
        synchronized (failNext) {
            failNext.add(kind);
        }
    }

    /** The next transactional call blocks for the given time before proceeding. */
    public void delayNext(long millis) {
        delayNextMs = millis;
    }

    /** The next placeOrder reaches the broker but the acknowledgement is lost: the caller sees TIMEOUT. */
    public void dropAck() {
        dropAck.set(true);
    }

    /** The broker invalidates the session: the next call fails with AUTH and the adapter reports DISCONNECTED. */
    public void simulateSessionExpiry() {
        brokerSideValid = false;
    }

    /** Fills part of an open order from the broker side (as if the exchange executed a slice). */
    public synchronized void partialFill(String brokerOrderId, int quantity, BigDecimal price) {
        FakeOrder order = open(new BrokerOrderRef(brokerOrderId));
        fill(order, Math.min(quantity, order.pending()), price.setScale(2, RoundingMode.HALF_UP));
    }

    /** Rejects an open order from the broker side. */
    public synchronized void rejectFromBroker(String brokerOrderId, String reason) {
        FakeOrder order = open(new BrokerOrderRef(brokerOrderId));
        order.status = BrokerOrderStatus.REJECTED;
        order.message = reason;
        order.updatedAt = clock.now();
        publish(order);
    }

    /** Seeds an order the broker knows about but Hejje did not create (external order); returns its broker id. */
    public synchronized String seedOrder(UUID instrumentId, Side side, int quantity, OrderType type, Product product,
            BigDecimal limit, BrokerOrderStatus status, int filled, BigDecimal avgPrice) {
        FakeOrder order = new FakeOrder();
        order.id = nextId();
        order.instrumentId = instrumentId;
        Optional<BrokerInstrumentRef> ref = instruments.forInstrument(instrumentId, BROKER_CODE);
        order.tradingSymbol = ref.map(BrokerInstrumentRef::tradingSymbol).orElse(instrumentId.toString());
        order.exchangeSegment = ref.map(BrokerInstrumentRef::exchangeSegment).orElse("NSE");
        order.side = side;
        order.quantity = quantity;
        order.type = type;
        order.product = product;
        order.limit = limit;
        order.validity = Validity.DAY;
        order.status = status;
        order.filled = filled;
        order.avgPrice = avgPrice == null ? BigDecimal.ZERO.setScale(2) : avgPrice;
        order.placedAt = clock.now();
        order.updatedAt = order.placedAt;
        orders.put(order.id, order);
        return order.id;
    }

    /** Seeds a broker-side position (as if trades happened outside Hejje). */
    public synchronized void seedPosition(UUID instrumentId, Product product, int netQuantity, BigDecimal averagePrice) {
        FakePosition p = position(instrumentId, product);
        p.net = netQuantity;
        p.avg = averagePrice.setScale(2, RoundingMode.HALF_UP);
        if (netQuantity > 0) {
            p.dayBuy += netQuantity;
            p.dayBuyValue = p.dayBuyValue.add(p.avg.multiply(BigDecimal.valueOf(netQuantity)));
        } else if (netQuantity < 0) {
            p.daySell += -netQuantity;
            p.daySellValue = p.daySellValue.add(p.avg.multiply(BigDecimal.valueOf(-netQuantity)));
        }
    }

    public void seedHistory(UUID instrumentId, Timeframe timeframe, List<BrokerCandle> candles) {
        history.put(instrumentId + "/" + timeframe, List.copyOf(candles));
    }

    /** Blocks until every queued order update has been delivered. */
    public void flush() {
        try {
            updateThread.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("fake broker update thread did not drain", e);
        }
    }

    /** Clears orders, trades, positions, quotes, scripts and funds. */
    public synchronized void reset() {
        orders.clear();
        trades.clear();
        positions.clear();
        quotes.clear();
        history.clear();
        synchronized (failNext) {
            failNext.clear();
        }
        dropAck.set(false);
        delayNextMs = 0;
        cash = startingCapital;
        connected = true;
        brokerSideValid = true;
        // sequence is intentionally not reset so broker order ids stay unique across resets
    }

    public Optional<BigDecimal> lastPrice(UUID instrumentId) {
        return Optional.ofNullable(quotes.get(instrumentId)).map(Quote::lastPrice);
    }

    // --- internals -------------------------------------------------------------------------------------------------------

    private void requireSession(String op) {
        if (!brokerSideValid) {
            boolean wasConnected = connected;
            connected = false;
            if (wasConnected) {
                events.publishEvent(new BrokerAuthRejected(BROKER_CODE, op + ": simulated token expiry"));
            }
            throw new BrokerException(BrokerException.Kind.AUTH, "Incorrect `api_key` or `access_token`.", false, null);
        }
        if (!connected) {
            throw new BrokerException(BrokerException.Kind.AUTH, "no broker session", false, null);
        }
    }

    private void script() {
        long delay = delayNextMs;
        if (delay > 0) {
            delayNextMs = 0;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        BrokerException.Kind kind;
        synchronized (failNext) {
            kind = failNext.poll();
        }
        if (kind != null) {
            throw new BrokerException(kind, "simulated " + kind, kind == BrokerException.Kind.NETWORK
                    || kind == BrokerException.Kind.TIMEOUT || kind == BrokerException.Kind.RATE_LIMIT, null);
        }
    }

    private FakeOrder open(BrokerOrderRef ref) {
        FakeOrder order = orders.get(ref.brokerOrderId());
        if (order == null) {
            throw new BrokerException(BrokerException.Kind.INPUT, "no such order " + ref.brokerOrderId(), false, null);
        }
        if (order.status.isTerminal()) {
            throw new BrokerException(BrokerException.Kind.REJECTED, "order " + ref.brokerOrderId() + " is " + order.status, false, null);
        }
        return order;
    }

    private String nextId() {
        return Long.toString(260908000000000L + sequence.getAndIncrement());
    }

    private void match(FakeOrder order, Quote quote) {
        if (quote == null || order.status.isTerminal() || order.pending() <= 0) {
            return;
        }
        BigDecimal ltp = quote.lastPrice();
        if ((order.type == OrderType.SL || order.type == OrderType.SL_M) && !order.triggered) {
            boolean crossed = order.side == Side.BUY ? ltp.compareTo(order.trigger) >= 0 : ltp.compareTo(order.trigger) <= 0;
            if (!crossed) {
                return;
            }
            order.triggered = true;
            order.status = BrokerOrderStatus.OPEN;
            order.updatedAt = clock.now();
            publish(order);
        }
        switch (order.type) {
            case MARKET, SL_M -> fill(order, order.pending(), ltp);
            case LIMIT, SL -> {
                boolean crosses = order.side == Side.BUY ? ltp.compareTo(order.limit) <= 0 : ltp.compareTo(order.limit) >= 0;
                if (crosses) {
                    fill(order, order.pending(), order.limit);
                }
            }
        }
    }

    private void fill(FakeOrder order, int quantity, BigDecimal price) {
        if (quantity <= 0) {
            return;
        }
        BigDecimal previousValue = order.avgPrice.multiply(BigDecimal.valueOf(order.filled));
        order.filled += quantity;
        order.avgPrice = previousValue.add(price.multiply(BigDecimal.valueOf(quantity)))
                .divide(BigDecimal.valueOf(order.filled), 2, RoundingMode.HALF_UP);
        order.status = order.pending() == 0 ? BrokerOrderStatus.COMPLETE : BrokerOrderStatus.OPEN;
        order.message = order.pending() == 0 ? "" : "partially filled";
        order.updatedAt = clock.now();
        Instant ts = clock.now();
        trades.add(new BrokerTrade(Long.toString(sequence.getAndIncrement()), order.id, order.instrumentId, order.tradingSymbol,
                order.exchangeSegment, order.side, order.product, quantity, price, ts, Map.of("simulated", true)));
        applyFill(order, quantity, price);
        publish(order);
    }

    private void applyFill(FakeOrder order, int quantity, BigDecimal price) {
        FakePosition p = position(order.instrumentId, order.product);
        p.tradingSymbol = order.tradingSymbol;
        p.exchangeSegment = order.exchangeSegment;
        int signed = order.side == Side.BUY ? quantity : -quantity;
        if (order.side == Side.BUY) {
            p.dayBuy += quantity;
            p.dayBuyValue = p.dayBuyValue.add(price.multiply(BigDecimal.valueOf(quantity)));
        } else {
            p.daySell += quantity;
            p.daySellValue = p.daySellValue.add(price.multiply(BigDecimal.valueOf(quantity)));
        }
        if (p.net == 0 || Integer.signum(p.net) == Integer.signum(signed)) {
            BigDecimal total = p.avg.multiply(BigDecimal.valueOf(Math.abs(p.net))).add(price.multiply(BigDecimal.valueOf(quantity)));
            p.net += signed;
            p.avg = total.divide(BigDecimal.valueOf(Math.abs(p.net)), 2, RoundingMode.HALF_UP);
        } else {
            int closing = Math.min(Math.abs(p.net), quantity);
            BigDecimal perUnit = p.net > 0 ? price.subtract(p.avg) : p.avg.subtract(price);
            p.realized = p.realized.plus(Money.of(perUnit.multiply(BigDecimal.valueOf(closing)).setScale(2, RoundingMode.HALF_UP)));
            cash = cash.plus(Money.of(perUnit.multiply(BigDecimal.valueOf(closing)).setScale(2, RoundingMode.HALF_UP)));
            int remaining = quantity - closing;
            p.net += signed;
            if (p.net == 0) {
                p.avg = BigDecimal.ZERO.setScale(2);
            } else if (remaining > 0) {
                p.avg = price;
            }
        }
    }

    private FakePosition position(UUID instrumentId, Product product) {
        return positions.computeIfAbsent(instrumentId + "/" + product, k -> {
            FakePosition p = new FakePosition();
            p.instrumentId = instrumentId;
            p.product = product;
            Optional<BrokerInstrumentRef> ref = instruments.forInstrument(instrumentId, BROKER_CODE);
            p.tradingSymbol = ref.map(BrokerInstrumentRef::tradingSymbol).orElse(instrumentId.toString());
            p.exchangeSegment = ref.map(BrokerInstrumentRef::exchangeSegment).orElse("NSE");
            return p;
        });
    }

    private Money marginFor(Product product, BigDecimal price, int quantity) {
        BigDecimal notional = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal margin = product == Product.CNC ? notional : notional.multiply(MARGIN_RATE);
        return Money.of(margin.setScale(2, RoundingMode.HALF_UP));
    }

    private void publish(FakeOrder order) {
        BrokerOrder snapshot = toBrokerOrder(order);
        updateThread.execute(() -> updates.publish(new BrokerOrderUpdate(snapshot, BrokerOrderUpdate.Source.BROKER_WS, clock.now())));
    }

    private BrokerOrder toBrokerOrder(FakeOrder o) {
        return new BrokerOrder(o.id, o.instrumentId, o.tradingSymbol, o.exchangeSegment, o.side, o.quantity, o.filled, o.pending(),
                o.avgPrice, o.type, o.product, o.limit == null ? BigDecimal.ZERO.setScale(2) : o.limit,
                o.trigger == null ? BigDecimal.ZERO.setScale(2) : o.trigger, o.validity, o.status, o.status.name(), o.message, o.tag,
                null, o.placedAt, o.updatedAt, Map.of("simulated", true));
    }

    private BrokerPosition toBrokerPosition(FakePosition p) {
        BigDecimal ltp = Optional.ofNullable(quotes.get(p.instrumentId)).map(Quote::lastPrice).orElse(p.avg);
        Money unrealized = p.net == 0 ? Money.ZERO
                : Money.of(ltp.subtract(p.avg).multiply(BigDecimal.valueOf(p.net)).setScale(2, RoundingMode.HALF_UP));
        return new BrokerPosition(p.instrumentId, p.tradingSymbol, p.exchangeSegment, p.product, p.net, p.avg, p.dayBuy, p.daySell,
                p.dayBuyValue.setScale(2, RoundingMode.HALF_UP), p.daySellValue.setScale(2, RoundingMode.HALF_UP), p.realized, unrealized, ltp,
                Map.of("simulated", true));
    }

    /** A fake streaming handle: ticks injected into the adapter are forwarded for subscribed instruments. */
    private final class FakeStream implements MarketDataStream {

        private final MarketDataListener listener;
        private final Map<UUID, MarketTick.Mode> subscriptions = new ConcurrentHashMap<>();
        private volatile boolean open = true;

        FakeStream(MarketDataListener listener) {
            this.listener = listener;
        }

        void emit(MarketTick tick) {
            MarketTick.Mode mode = subscriptions.get(tick.instrumentId());
            if (!open || mode == null) {
                return;
            }
            MarketTick delivered = mode == MarketTick.Mode.LTP ? MarketTick.ltp(tick.instrumentId(), tick.ts(), tick.lastPrice())
                    : new MarketTick(tick.instrumentId(), tick.ts(), tick.lastPrice(), tick.bid(), tick.ask(), tick.volume(), tick.oi(), mode);
            try {
                listener.onTick(delivered);
            } catch (RuntimeException e) {
                log.warn("Fake stream listener failed", e);
            }
        }

        @Override
        public void subscribe(Set<UUID> instrumentIds, MarketTick.Mode mode) {
            instrumentIds.forEach(id -> subscriptions.put(id, mode));
        }

        @Override
        public void unsubscribe(Set<UUID> instrumentIds) {
            instrumentIds.forEach(subscriptions::remove);
        }

        @Override
        public void setMode(Set<UUID> instrumentIds, MarketTick.Mode mode) {
            instrumentIds.forEach(id -> subscriptions.computeIfPresent(id, (k, v) -> mode));
        }

        @Override
        public boolean isConnected() {
            return open && connected;
        }

        @Override
        public void close() {
            open = false;
            streams.remove(this);
            listener.onDisconnected("closed");
        }

        /** Test hook: simulate a broker-side disconnect. */
        public void drop(String reason) {
            open = false;
            listener.onDisconnected(reason);
        }
    }

    /** Simulates the broker dropping every streaming connection (streams must be reopened). */
    public void dropStreams(String reason) {
        for (FakeStream s : List.copyOf(streams)) {
            s.drop(reason);
            streams.remove(s);
        }
    }
}
