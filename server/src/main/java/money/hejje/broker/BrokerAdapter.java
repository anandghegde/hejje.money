package money.hejje.broker;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.Timeframe;

/**
 * Broker-neutral contract (PRD section 5.2). Every broker call the daemon makes goes through this interface and
 * callers only see Hejje models; failures are {@link BrokerException}s. Order state changes reach the rest of the
 * system through {@link BrokerOrderUpdates}, whichever channel (WebSocket, postback, poll, simulation) produced them.
 */
public interface BrokerAdapter {

    /** Stable broker code used in {@code hejje_order.broker}. */
    String brokerCode();

    /**
     * Broker code the instrument master from {@link #getInstruments()} is stored under in
     * {@code broker_instrument_mapping.broker}. Wrappers that delegate market data (paper) return the delegate's code so
     * that lookups by the delegate find the mappings.
     */
    default String instrumentBrokerCode() {
        return brokerCode();
    }

    // --- session -------------------------------------------------------------------------------------------------

    /** URL the user opens in a browser to log in at the broker. */
    String loginUrl();

    /** Exchanges the broker's request token for a session. The returned access token must be stored encrypted. */
    BrokerSession authenticate(String requestToken);

    /** Re-installs a previously stored access token (after a restart). Does not call the broker. */
    void restoreSession(String accessToken);

    /** Forgets the session locally (expiry, logout) without calling the broker. */
    void clearSession();

    /** Invalidates the session at the broker and forgets it locally. */
    void logout();

    BrokerSessionState sessionState();

    /** Validates the session with a cheap authenticated call. Throws {@link BrokerException} of kind AUTH when invalid. */
    BrokerProfile getProfile();

    // --- market data ----------------------------------------------------------------------------------------------

    List<Quote> getQuote(Set<UUID> instrumentIds);

    /** Historical candles, both bounds inclusive, in exchange time. */
    List<BrokerCandle> getHistory(UUID instrumentId, Timeframe timeframe, Instant from, Instant to);

    /** Opens the broker streaming connection; the returned handle manages subscriptions. */
    MarketDataStream streamMarketData(MarketDataListener listener);

    List<BrokerInstrument> getInstruments();

    // --- transactional ----------------------------------------------------------------------------------------------

    BrokerOrderRef placeOrder(BrokerOrderRequest request);

    BrokerOrderRef modifyOrder(BrokerOrderRef ref, BrokerModifyRequest request);

    BrokerOrderRef cancelOrder(BrokerOrderRef ref);

    // --- reads --------------------------------------------------------------------------------------------------

    /** The latest state of one order (broker order history, most recent entry). */
    BrokerOrder getOrder(BrokerOrderRef ref);

    List<BrokerOrder> getOrders();

    List<BrokerTrade> getTrades();

    List<BrokerPosition> getPositions();

    List<BrokerHolding> getHoldings();

    Funds getFunds();

    List<OrderMargin> getOrderMargins(List<BrokerOrderRequest> requests);
}
