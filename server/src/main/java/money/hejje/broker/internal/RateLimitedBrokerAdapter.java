package money.hejje.broker.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerCandle;
import money.hejje.broker.BrokerHolding;
import money.hejje.broker.BrokerInstrument;
import money.hejje.broker.BrokerModifyRequest;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerOrderRequest;
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
import money.hejje.common.Timeframe;

/**
 * Wraps the active {@link BrokerAdapter} with the rate limiter and per-operation latency timers ({@code broker.call{op}}).
 * The primary {@code BrokerAdapter} bean, so everything that talks to the broker through the interface is limited and timed.
 */
public class RateLimitedBrokerAdapter implements BrokerAdapter {

    private final BrokerAdapter delegate;
    private final BrokerRateLimiter limiter;
    private final MeterRegistry meters;

    public RateLimitedBrokerAdapter(BrokerAdapter delegate, BrokerRateLimiter limiter, MeterRegistry meters) {
        this.delegate = delegate;
        this.limiter = limiter;
        this.meters = meters;
    }

    private <T> T timed(String op, java.util.function.Supplier<T> call) {
        Timer.Sample sample = Timer.start(meters);
        try {
            return call.get();
        } finally {
            sample.stop(Timer.builder("broker.call").tag("op", op).publishPercentileHistogram().register(meters));
        }
    }

    @Override public String brokerCode() { return delegate.brokerCode(); }
    @Override public String loginUrl() { return delegate.loginUrl(); }
    @Override public BrokerSession authenticate(String requestToken) { return delegate.authenticate(requestToken); }
    @Override public void restoreSession(String accessToken) { delegate.restoreSession(accessToken); }
    @Override public void clearSession() { delegate.clearSession(); }
    @Override public void logout() { delegate.logout(); }
    @Override public BrokerSessionState sessionState() { return delegate.sessionState(); }

    @Override public BrokerProfile getProfile() {
        limiter.acquireRead(BrokerRateLimiter.Op.GENERAL);
        return timed("getProfile", delegate::getProfile);
    }

    @Override public List<Quote> getQuote(Set<UUID> instrumentIds) {
        limiter.acquireRead(BrokerRateLimiter.Op.QUOTE);
        return timed("getQuote", () -> delegate.getQuote(instrumentIds));
    }

    @Override public List<BrokerCandle> getHistory(UUID instrumentId, Timeframe timeframe, Instant from, Instant to) {
        limiter.acquireRead(BrokerRateLimiter.Op.HISTORICAL);
        return timed("getHistory", () -> delegate.getHistory(instrumentId, timeframe, from, to));
    }

    @Override public MarketDataStream streamMarketData(MarketDataListener listener) { return delegate.streamMarketData(listener); }

    @Override public List<BrokerInstrument> getInstruments() {
        limiter.acquireRead(BrokerRateLimiter.Op.GENERAL);
        return timed("getInstruments", delegate::getInstruments);
    }

    @Override public BrokerOrderRef placeOrder(BrokerOrderRequest request) {
        limiter.acquireOrder();
        Timer.Sample ack = Timer.start(meters);
        try {
            return timed("placeOrder", () -> delegate.placeOrder(request));
        } finally {
            ack.stop(Timer.builder("broker.ack").publishPercentileHistogram().register(meters));
        }
    }

    @Override public BrokerOrderRef modifyOrder(BrokerOrderRef ref, BrokerModifyRequest request) {
        limiter.acquireOrder();
        return timed("modifyOrder", () -> delegate.modifyOrder(ref, request));
    }

    @Override public BrokerOrderRef cancelOrder(BrokerOrderRef ref) {
        limiter.acquireOrder();
        return timed("cancelOrder", () -> delegate.cancelOrder(ref));
    }

    @Override public BrokerOrder getOrder(BrokerOrderRef ref) {
        limiter.acquireRead(BrokerRateLimiter.Op.GENERAL);
        return timed("getOrder", () -> delegate.getOrder(ref));
    }

    @Override public List<BrokerOrder> getOrders() {
        limiter.acquireRead(BrokerRateLimiter.Op.GENERAL);
        return timed("getOrders", delegate::getOrders);
    }

    @Override public List<BrokerTrade> getTrades() {
        limiter.acquireRead(BrokerRateLimiter.Op.GENERAL);
        return timed("getTrades", delegate::getTrades);
    }

    @Override public List<BrokerPosition> getPositions() {
        limiter.acquireRead(BrokerRateLimiter.Op.GENERAL);
        return timed("getPositions", delegate::getPositions);
    }

    @Override public List<BrokerHolding> getHoldings() {
        limiter.acquireRead(BrokerRateLimiter.Op.GENERAL);
        return timed("getHoldings", delegate::getHoldings);
    }

    @Override public Funds getFunds() {
        limiter.acquireRead(BrokerRateLimiter.Op.GENERAL);
        return timed("getFunds", delegate::getFunds);
    }

    @Override public List<OrderMargin> getOrderMargins(List<BrokerOrderRequest> requests) {
        limiter.acquireRead(BrokerRateLimiter.Op.GENERAL);
        return timed("getOrderMargins", () -> delegate.getOrderMargins(requests));
    }
}
