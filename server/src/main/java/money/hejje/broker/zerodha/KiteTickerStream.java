package money.hejje.broker.zerodha;

import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerOrderUpdate;
import money.hejje.broker.BrokerOrderUpdates;
import money.hejje.broker.MarketDataListener;
import money.hejje.broker.MarketDataStream;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Kite WebSocket connection: binary ticks become {@link MarketTick}s for the listener, {@code order} text messages
 * become {@link BrokerOrderUpdate}s on the shared bus. The library's own reconnection is enabled; the market module
 * layers its backoff and gap detection on top.
 */
final class KiteTickerStream implements MarketDataStream {

    private static final Logger log = LoggerFactory.getLogger(KiteTickerStream.class);

    private final KiteTicker ticker;
    private final BrokerInstrumentResolver instruments;
    private final Map<Long, UUID> tokenToInstrument = new ConcurrentHashMap<>();
    private final Map<UUID, Long> instrumentToToken = new ConcurrentHashMap<>();
    private final HejjeClock clock;
    private volatile boolean connected;

    KiteTickerStream(String accessToken, String apiKey, BrokerInstrumentResolver instruments, BrokerOrderUpdates orderUpdates,
            MarketDataListener listener, HejjeClock clock) {
        this.instruments = instruments;
        this.clock = clock;
        this.ticker = new KiteTicker(accessToken, apiKey);
        ticker.setTryReconnection(true);
        try {
            ticker.setMaximumRetries(10);
            ticker.setMaximumRetryInterval(30);
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException e) {
            log.warn("Could not configure ticker retries: {}", e.message);
        }
        ticker.setOnConnectedListener(() -> {
            connected = true;
            listener.onConnected();
        });
        ticker.setOnDisconnectedListener(() -> {
            connected = false;
            listener.onDisconnected("ticker disconnected");
        });
        ticker.setOnErrorListener(new com.zerodhatech.ticker.OnError() {
            @Override
            public void onError(Exception exception) {
                listener.onError(exception);
            }

            @Override
            public void onError(com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException kiteException) {
                listener.onError(KiteMapper.toBrokerException(kiteException));
            }

            @Override
            public void onError(String error) {
                listener.onError(new IllegalStateException(error));
            }
        });
        ticker.setOnTickerArrivalListener(ticks -> {
            for (Tick t : ticks) {
                UUID id = tokenToInstrument.get(t.getInstrumentToken());
                if (id != null) {
                    listener.onTick(KiteMapper.tick(t, id, clock.now()));
                }
            }
        });
        ticker.setOnOrderUpdateListener(order -> orderUpdates.publish(new BrokerOrderUpdate(
                KiteMapper.order(order, o -> instruments.byTradingSymbol(ZerodhaKiteAdapter.BROKER_CODE, o.exchange, o.tradingSymbol).orElse(null)),
                BrokerOrderUpdate.Source.BROKER_WS, clock.now())));
        ticker.connect();
    }

    @Override
    public void subscribe(Set<UUID> instrumentIds, MarketTick.Mode mode) {
        ArrayList<Long> tokens = tokens(instrumentIds, true);
        if (tokens.isEmpty()) {
            return;
        }
        ticker.subscribe(tokens);
        ticker.setMode(tokens, KiteMapper.tickMode(mode));
    }

    @Override
    public void unsubscribe(Set<UUID> instrumentIds) {
        ArrayList<Long> tokens = tokens(instrumentIds, false);
        if (!tokens.isEmpty()) {
            ticker.unsubscribe(tokens);
        }
        for (UUID id : instrumentIds) {
            Long token = instrumentToToken.remove(id);
            if (token != null) {
                tokenToInstrument.remove(token);
            }
        }
    }

    @Override
    public void setMode(Set<UUID> instrumentIds, MarketTick.Mode mode) {
        ArrayList<Long> tokens = tokens(instrumentIds, false);
        if (!tokens.isEmpty()) {
            ticker.setMode(tokens, KiteMapper.tickMode(mode));
        }
    }

    @Override
    public boolean isConnected() {
        return connected && ticker.isConnectionOpen();
    }

    @Override
    public void close() {
        connected = false;
        ticker.disconnect();
    }

    private ArrayList<Long> tokens(Set<UUID> instrumentIds, boolean register) {
        ArrayList<Long> tokens = new ArrayList<>();
        for (UUID id : instrumentIds) {
            Long token = instrumentToToken.get(id);
            if (token == null && register) {
                token = instruments.forInstrument(id, ZerodhaKiteAdapter.BROKER_CODE).map(r -> Long.parseLong(r.brokerToken())).orElse(null);
                if (token == null) {
                    log.warn("Instrument {} has no Kite token; not subscribed", id);
                    continue;
                }
                instrumentToToken.put(id, token);
                tokenToInstrument.put(token, id);
            }
            if (token != null) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
