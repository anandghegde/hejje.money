package money.hejje.broker.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/**
 * Paper trading adapter (PRD section 50): transactional operations are simulated against live prices, while quotes,
 * history, instruments, streaming and the broker session delegate to a real adapter. MARKET fills at the last price with
 * slippage; LIMIT and SL fill when a tick crosses. Positions and funds are simulated; fees are applied by the orders
 * layer via the cost model. Structurally separate from live: it never places a real order.
 */
public class PaperBrokerAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PaperBrokerAdapter.class);
    public static final String BROKER_CODE = "paper";
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10000);

    private final BrokerAdapter delegate;
    private final BrokerInstrumentResolver instruments;
    private final BrokerOrderUpdates updates;
    private final HejjeClock clock;
    private final PaperBrokerProperties properties;

    private final Map<UUID, BigDecimal> lastPrice = new ConcurrentHashMap<>();
    private final Map<String, PaperOrder> orders = new LinkedHashMap<>();
    private final List<BrokerTrade> trades = new java.util.ArrayList<>();
    private final Map<String, PaperPosition> positions = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);
    private final ExecutorService updateThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "paper-broker-updates");
        t.setDaemon(true);
        return t;
    });
    private final Money startingCapital;
    private Money cash;

    public PaperBrokerAdapter(BrokerAdapter delegate, BrokerInstrumentResolver instruments, BrokerOrderUpdates updates,
            HejjeClock clock, PaperBrokerProperties properties) {
        this.delegate = delegate;
        this.instruments = instruments;
        this.updates = updates;
        this.clock = clock;
        this.properties = properties;
        this.startingCapital = Money.ofRupees(properties.startingCapital());
        this.cash = startingCapital;
    }

    private static final class PaperOrder {
        String id; UUID instrumentId; String tradingSymbol; String exchangeSegment; Side side; int quantity; int filled;
        BigDecimal avg = BigDecimal.ZERO.setScale(2); OrderType type; Product product; BigDecimal limit; BigDecimal trigger;
        Validity validity; BrokerOrderStatus status; String message = ""; String tag; Instant placedAt; Instant updatedAt; boolean triggered;
        int pending() { return quantity - filled; }
    }

    private static final class PaperPosition {
        UUID instrumentId; String tradingSymbol; String exchangeSegment; Product product; int net; BigDecimal avg = BigDecimal.ZERO.setScale(2);
        Money realized = Money.ZERO; int dayBuy; int daySell;
    }

    @Override public String brokerCode() { return BROKER_CODE; }

    // --- session and market data delegate to the real adapter --------------------------------------------------------

    @Override public String loginUrl() { return delegate.loginUrl(); }
    @Override public BrokerSession authenticate(String requestToken) { return delegate.authenticate(requestToken); }
    @Override public void restoreSession(String accessToken) { delegate.restoreSession(accessToken); }
    @Override public void clearSession() { delegate.clearSession(); }
    @Override public void logout() { delegate.logout(); }
    @Override public BrokerSessionState sessionState() { return delegate.sessionState(); }
    @Override public BrokerProfile getProfile() { return delegate.getProfile(); }
    @Override public List<Quote> getQuote(Set<UUID> instrumentIds) {
        List<Quote> quotes = delegate.getQuote(instrumentIds);
        quotes.forEach(q -> lastPrice.put(q.instrumentId(), q.lastPrice()));
        return quotes;
    }
    @Override public List<BrokerCandle> getHistory(UUID instrumentId, Timeframe tf, Instant from, Instant to) {
        return delegate.getHistory(instrumentId, tf, from, to);
    }
    @Override public List<BrokerInstrument> getInstruments() { return delegate.getInstruments(); }

    @Override public MarketDataStream streamMarketData(MarketDataListener listener) {
        // tap the live stream to drive fills, then forward to the caller
        return delegate.streamMarketData(new MarketDataListener() {
            @Override public void onTick(MarketTick tick) {
                injectTick(tick);
                listener.onTick(tick);
            }
            @Override public void onConnected() { listener.onConnected(); }
            @Override public void onDisconnected(String reason) { listener.onDisconnected(reason); }
            @Override public void onError(Throwable error) { listener.onError(error); }
        });
    }

    // --- transactional: simulated ------------------------------------------------------------------------------------

    @Override public synchronized BrokerOrderRef placeOrder(BrokerOrderRequest request) {
        PaperOrder order = new PaperOrder();
        order.id = "PAPER" + sequence.getAndIncrement();
        order.instrumentId = request.instrumentId();
        Optional<BrokerInstrumentRef> ref = instruments.forInstrument(request.instrumentId(), delegate.brokerCode());
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
        publish(order);
        BigDecimal ltp = currentPrice(order.instrumentId);
        if (ltp != null) {
            match(order, ltp);
        }
        return new BrokerOrderRef(order.id);
    }

    @Override public synchronized BrokerOrderRef modifyOrder(BrokerOrderRef ref, BrokerModifyRequest request) {
        PaperOrder order = open(ref);
        if (request.quantity() != null) order.quantity = request.quantity().value();
        if (request.orderType() != null) order.type = request.orderType();
        if (request.limitPrice() != null) order.limit = request.limitPrice().value();
        if (request.triggerPrice() != null) order.trigger = request.triggerPrice().value();
        order.updatedAt = clock.now();
        publish(order);
        BigDecimal ltp = currentPrice(order.instrumentId);
        if (ltp != null) match(order, ltp);
        return new BrokerOrderRef(order.id);
    }

    @Override public synchronized BrokerOrderRef cancelOrder(BrokerOrderRef ref) {
        PaperOrder order = open(ref);
        order.status = BrokerOrderStatus.CANCELLED;
        order.message = "cancelled";
        order.updatedAt = clock.now();
        publish(order);
        return new BrokerOrderRef(order.id);
    }

    @Override public synchronized BrokerOrder getOrder(BrokerOrderRef ref) {
        PaperOrder order = orders.get(ref.brokerOrderId());
        if (order == null) {
            throw new BrokerException(BrokerException.Kind.INPUT, "no such paper order " + ref.brokerOrderId(), false, null);
        }
        return toBrokerOrder(order);
    }

    @Override public synchronized List<BrokerOrder> getOrders() { return orders.values().stream().map(this::toBrokerOrder).toList(); }
    @Override public synchronized List<BrokerTrade> getTrades() { return List.copyOf(trades); }
    @Override public synchronized List<BrokerPosition> getPositions() { return positions.values().stream().map(this::toBrokerPosition).toList(); }
    @Override public List<BrokerHolding> getHoldings() { return List.of(); }

    @Override public synchronized Funds getFunds() {
        return new Funds(cash, startingCapital.minus(cash).abs(), cash, startingCapital, Map.of("simulated", true, "paper", true));
    }

    @Override public List<OrderMargin> getOrderMargins(List<BrokerOrderRequest> requests) {
        List<OrderMargin> out = new java.util.ArrayList<>();
        for (BrokerOrderRequest r : requests) {
            BigDecimal price = r.limitPrice() != null ? r.limitPrice().value() : Optional.ofNullable(currentPrice(r.instrumentId())).orElse(BigDecimal.ZERO);
            Money total = Money.of(price.multiply(BigDecimal.valueOf(r.quantity().value())).multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP));
            out.add(new OrderMargin(r.instrumentId(), total, Money.ZERO, total, Money.ZERO, Money.ZERO, Money.ZERO));
        }
        return out;
    }

    // --- simulation --------------------------------------------------------------------------------------------------

    /** Feeds a tick: updates the last price and matches resting orders. Public for tests; also driven by the live stream. */
    public synchronized void injectTick(MarketTick tick) {
        lastPrice.put(tick.instrumentId(), tick.lastPrice());
        for (PaperOrder order : List.copyOf(orders.values())) {
            if (order.instrumentId.equals(tick.instrumentId()) && !order.status.isTerminal()) {
                match(order, tick.lastPrice());
            }
        }
    }

    private BigDecimal currentPrice(UUID instrumentId) {
        BigDecimal cached = lastPrice.get(instrumentId);
        if (cached != null) {
            return cached;
        }
        try {
            List<Quote> quotes = delegate.getQuote(Set.of(instrumentId));
            if (!quotes.isEmpty()) {
                lastPrice.put(instrumentId, quotes.get(0).lastPrice());
                return quotes.get(0).lastPrice();
            }
        } catch (BrokerException e) {
            log.debug("Paper: no quote for {} ({})", instrumentId, e.kind());
        }
        return null;
    }

    private void match(PaperOrder order, BigDecimal ltp) {
        if (order.status.isTerminal() || order.pending() <= 0) {
            return;
        }
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
            case MARKET, SL_M -> fill(order, order.pending(), slipped(order.side, ltp));
            case LIMIT, SL -> {
                boolean crosses = order.side == Side.BUY ? ltp.compareTo(order.limit) <= 0 : ltp.compareTo(order.limit) >= 0;
                if (crosses) {
                    fill(order, order.pending(), order.limit);
                }
            }
        }
    }

    private BigDecimal slipped(Side side, BigDecimal ltp) {
        BigDecimal factor = properties.slippageBps().divide(TEN_THOUSAND, 8, RoundingMode.HALF_UP);
        BigDecimal delta = ltp.multiply(factor);
        return (side == Side.BUY ? ltp.add(delta) : ltp.subtract(delta)).setScale(2, RoundingMode.HALF_UP);
    }

    private void fill(PaperOrder order, int quantity, BigDecimal price) {
        if (quantity <= 0) {
            return;
        }
        BigDecimal prev = order.avg.multiply(BigDecimal.valueOf(order.filled));
        order.filled += quantity;
        order.avg = prev.add(price.multiply(BigDecimal.valueOf(quantity))).divide(BigDecimal.valueOf(order.filled), 2, RoundingMode.HALF_UP);
        order.status = order.pending() == 0 ? BrokerOrderStatus.COMPLETE : BrokerOrderStatus.OPEN;
        order.updatedAt = clock.now();
        trades.add(new BrokerTrade(Long.toString(sequence.getAndIncrement()), order.id, order.instrumentId, order.tradingSymbol,
                order.exchangeSegment, order.side, order.product, quantity, price, clock.now(), Map.of("paper", true)));
        applyFill(order, quantity, price);
        publish(order);
    }

    private void applyFill(PaperOrder order, int quantity, BigDecimal price) {
        PaperPosition p = positions.computeIfAbsent(order.instrumentId + "/" + order.product, k -> {
            PaperPosition np = new PaperPosition();
            np.instrumentId = order.instrumentId;
            np.product = order.product;
            np.tradingSymbol = order.tradingSymbol;
            np.exchangeSegment = order.exchangeSegment;
            return np;
        });
        int signed = order.side == Side.BUY ? quantity : -quantity;
        if (order.side == Side.BUY) p.dayBuy += quantity; else p.daySell += quantity;
        if (p.net == 0 || Integer.signum(p.net) == Integer.signum(signed)) {
            BigDecimal total = p.avg.multiply(BigDecimal.valueOf(Math.abs(p.net))).add(price.multiply(BigDecimal.valueOf(quantity)));
            p.net += signed;
            p.avg = total.divide(BigDecimal.valueOf(Math.abs(p.net)), 2, RoundingMode.HALF_UP);
        } else {
            int closing = Math.min(Math.abs(p.net), quantity);
            BigDecimal perUnit = p.net > 0 ? price.subtract(p.avg) : p.avg.subtract(price);
            Money realizedDelta = Money.of(perUnit.multiply(BigDecimal.valueOf(closing)).setScale(2, RoundingMode.HALF_UP));
            p.realized = p.realized.plus(realizedDelta);
            cash = cash.plus(realizedDelta);
            int remaining = quantity - closing;
            p.net += signed;
            if (p.net == 0) p.avg = BigDecimal.ZERO.setScale(2);
            else if (remaining > 0) p.avg = price;
        }
    }

    private PaperOrder open(BrokerOrderRef ref) {
        PaperOrder order = orders.get(ref.brokerOrderId());
        if (order == null) {
            throw new BrokerException(BrokerException.Kind.INPUT, "no such paper order " + ref.brokerOrderId(), false, null);
        }
        if (order.status.isTerminal()) {
            throw new BrokerException(BrokerException.Kind.REJECTED, "paper order " + ref.brokerOrderId() + " is " + order.status, false, null);
        }
        return order;
    }

    private void publish(PaperOrder order) {
        BrokerOrder snapshot = toBrokerOrder(order);
        updateThread.execute(() -> updates.publish(new BrokerOrderUpdate(snapshot, BrokerOrderUpdate.Source.BROKER_WS, clock.now())));
    }

    private BrokerOrder toBrokerOrder(PaperOrder o) {
        return new BrokerOrder(o.id, o.instrumentId, o.tradingSymbol, o.exchangeSegment, o.side, o.quantity, o.filled, o.pending(),
                o.avg, o.type, o.product, o.limit == null ? BigDecimal.ZERO.setScale(2) : o.limit,
                o.trigger == null ? BigDecimal.ZERO.setScale(2) : o.trigger, o.validity, o.status, o.status.name(), o.message, o.tag,
                null, o.placedAt, o.updatedAt, Map.of("paper", true));
    }

    private BrokerPosition toBrokerPosition(PaperPosition p) {
        BigDecimal ltp = Optional.ofNullable(lastPrice.get(p.instrumentId)).orElse(p.avg);
        Money unrealized = p.net == 0 ? Money.ZERO : Money.of(ltp.subtract(p.avg).multiply(BigDecimal.valueOf(p.net)).setScale(2, RoundingMode.HALF_UP));
        return new BrokerPosition(p.instrumentId, p.tradingSymbol, p.exchangeSegment, p.product, p.net, p.avg, p.dayBuy, p.daySell,
                BigDecimal.ZERO, BigDecimal.ZERO, p.realized, unrealized, ltp, Map.of("paper", true));
    }

    public void flush() {
        try {
            updateThread.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("paper broker update thread did not drain", e);
        }
    }

    public boolean isPaper() {
        return true;
    }
}
