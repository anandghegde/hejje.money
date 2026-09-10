package money.hejje.broker.dhan;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.MarketDataListener;
import money.hejje.broker.MarketDataStream;
import money.hejje.broker.Quote;
import money.hejje.common.event.MarketTick;

/**
 * Market data for Dhan by polling the REST quote API (plan M5.6): every {@code quote-poll-interval} one request for
 * all subscribed instruments (Dhan allows one quote request per second, up to 1000 instruments), delivered as ticks in
 * each instrument's subscribed mode. Dhan's binary websocket feed is not implemented (docs/broker-dhan.md).
 */
final class DhanQuoteStream implements MarketDataStream {

    private final DhanAdapter adapter;
    private final MarketDataListener listener;
    private final Map<UUID, MarketTick.Mode> modes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dhan-quotes");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean open = true;

    DhanQuoteStream(DhanAdapter adapter, MarketDataListener listener, Duration interval) {
        this.adapter = adapter;
        this.listener = listener;
        poller.scheduleWithFixedDelay(this::poll, 0, Math.max(200, interval.toMillis()), TimeUnit.MILLISECONDS);
        listener.onConnected();
    }

    void poll() {
        if (!open || modes.isEmpty()) {
            return;
        }
        try {
            for (Quote q : adapter.getQuote(Set.copyOf(modes.keySet()))) {
                MarketTick.Mode mode = modes.getOrDefault(q.instrumentId(), MarketTick.Mode.LTP);
                listener.onTick(mode == MarketTick.Mode.LTP
                        ? new MarketTick(q.instrumentId(), q.ts(), q.lastPrice(), null, null, q.volume(), 0, mode)
                        : new MarketTick(q.instrumentId(), q.ts(), q.lastPrice(), q.bid(), q.ask(), q.volume(), q.oi(), mode));
            }
        } catch (BrokerException e) {
            listener.onError(e);
            if (e.kind() == BrokerException.Kind.AUTH) {
                close("session rejected");
            }
        } catch (RuntimeException e) {
            listener.onError(e);
        }
    }

    @Override
    public void subscribe(Set<UUID> instrumentIds, MarketTick.Mode mode) {
        instrumentIds.forEach(id -> modes.put(id, mode));
    }

    @Override
    public void unsubscribe(Set<UUID> instrumentIds) {
        instrumentIds.forEach(modes::remove);
    }

    @Override
    public void setMode(Set<UUID> instrumentIds, MarketTick.Mode mode) {
        subscribe(instrumentIds, mode);
    }

    @Override
    public boolean isConnected() {
        return open && adapter.sessionState() == BrokerSessionState.CONNECTED;
    }

    @Override
    public void close() {
        close("closed");
    }

    private void close(String reason) {
        if (open) {
            open = false;
            poller.shutdownNow();
            listener.onDisconnected(reason);
        }
    }
}
