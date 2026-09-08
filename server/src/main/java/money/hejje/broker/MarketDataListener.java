package money.hejje.broker;

import money.hejje.common.event.MarketTick;

/** Receives streamed market data and connection state from a {@link MarketDataStream}. */
public interface MarketDataListener {

    void onTick(MarketTick tick);

    default void onConnected() {
    }

    default void onDisconnected(String reason) {
    }

    default void onError(Throwable error) {
    }
}
