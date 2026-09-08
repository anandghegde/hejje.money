package money.hejje.broker;

import java.util.Set;
import java.util.UUID;
import money.hejje.common.event.MarketTick;

/** Handle to a broker streaming connection. Subscriptions are by Hejje instrument id. */
public interface MarketDataStream extends AutoCloseable {

    void subscribe(Set<UUID> instrumentIds, MarketTick.Mode mode);

    void unsubscribe(Set<UUID> instrumentIds);

    void setMode(Set<UUID> instrumentIds, MarketTick.Mode mode);

    boolean isConnected();

    @Override
    void close();
}
