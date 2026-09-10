package money.hejje.market.internal;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.MarketDataListener;
import money.hejje.broker.MarketDataStream;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.MarketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Manages the broker streaming connection: default watchlist in FULL mode, other subscriptions in LTP, reconnect with
 * exponential backoff, and gap detection (no tick for {@code staleAfter} during the session). Feeds every tick into the
 * {@link MarketPipeline}. Reports {@code marketData} readiness.
 */
@Component
public class MarketDataStreamer implements MarketDataListener {

    private static final Logger log = LoggerFactory.getLogger(MarketDataStreamer.class);

    private final BrokerAdapter broker;
    private final InstrumentService instruments;
    private final MarketPipeline pipeline;
    private final HejjeClock clock;
    private final MarketProperties properties;

    private final Set<UUID> watched = ConcurrentHashMap.newKeySet();
    private final Set<UUID> subscribed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> full = ConcurrentHashMap.newKeySet(); // FULL mode besides the watchlist (option chains need OI, M5.4)
    private volatile MarketDataStream stream;
    private volatile long backoffMs = 1000;
    private volatile boolean enabled;

    MarketDataStreamer(BrokerAdapter broker, InstrumentService instruments, MarketPipeline pipeline, HejjeClock clock, MarketProperties properties) {
        this.broker = broker;
        this.instruments = instruments;
        this.pipeline = pipeline;
        this.clock = clock;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (properties.streamOnStartup()) {
            resolveWatchlist();
            start();
        }
    }

    private void resolveWatchlist() {
        for (String symbol : properties.watchlist()) {
            try {
                instruments.resolve(symbol).ifPresent(i -> watched.add(i.id()));
            } catch (RuntimeException e) {
                log.warn("Watchlist symbol {} did not resolve: {}", symbol, e.getMessage());
            }
        }
        instruments.nearestFuture("NIFTY").ifPresent(i -> watched.add(i.id()));
        instruments.nearestFuture("BANKNIFTY").ifPresent(i -> watched.add(i.id()));
    }

    public synchronized void start() {
        if (stream != null && stream.isConnected()) {
            return;
        }
        if (broker.sessionState() != BrokerSessionState.CONNECTED) {
            log.info("Market streamer waiting for broker session");
            return;
        }
        try {
            stream = broker.streamMarketData(this);
            enabled = true;
            resubscribe();
            backoffMs = 1000;
        } catch (BrokerException e) {
            log.warn("Could not open market stream ({}): {}", e.kind(), e.brokerMessage());
        }
    }

    private void resubscribe() {
        if (stream == null) {
            return;
        }
        Set<UUID> fullMode = new LinkedHashSet<>(watched);
        fullMode.addAll(full);
        if (!fullMode.isEmpty()) {
            stream.subscribe(Set.copyOf(fullMode), MarketTick.Mode.FULL);
            subscribed.addAll(fullMode);
        }
        Set<UUID> ltp = new LinkedHashSet<>(subscribed);
        ltp.removeAll(fullMode);
        if (!ltp.isEmpty()) {
            stream.subscribe(ltp, MarketTick.Mode.LTP);
        }
    }

    /** LTP-mode subscription; an instrument already streamed in FULL mode (watchlist, option chains) keeps FULL. */
    public synchronized void subscribe(Set<UUID> instrumentIds) {
        subscribed.addAll(instrumentIds);
        Set<UUID> ltp = new LinkedHashSet<>(instrumentIds);
        ltp.removeAll(watched);
        ltp.removeAll(full);
        if (stream != null && stream.isConnected()) {
            if (!ltp.isEmpty()) {
                stream.subscribe(Set.copyOf(ltp), MarketTick.Mode.LTP);
            }
        } else {
            start();
        }
    }

    /** FULL-mode subscription (depth, volume and open interest), e.g. for an option chain (M5.4). */
    public synchronized void subscribeFull(Set<UUID> instrumentIds) {
        full.addAll(instrumentIds);
        subscribed.addAll(instrumentIds);
        if (stream != null && stream.isConnected()) {
            stream.subscribe(Set.copyOf(instrumentIds), MarketTick.Mode.FULL);
        } else {
            start();
        }
    }

    public synchronized void unsubscribe(Set<UUID> instrumentIds) {
        Set<UUID> removable = new LinkedHashSet<>(instrumentIds);
        removable.removeAll(watched); // never drop the default watchlist
        subscribed.removeAll(removable);
        full.removeAll(removable);
        if (stream != null && !removable.isEmpty()) {
            stream.unsubscribe(removable);
        }
    }

    public Set<UUID> subscriptions() {
        return Collections.unmodifiableSet(subscribed);
    }

    @Override
    public void onTick(MarketTick tick) {
        pipeline.onTick(tick);
    }

    @Override
    public void onConnected() {
        log.info("Market stream connected");
        backoffMs = 1000;
    }

    @Override
    public void onDisconnected(String reason) {
        log.warn("Market stream disconnected: {}", reason);
    }

    @Override
    public void onError(Throwable error) {
        log.warn("Market stream error: {}", error.getMessage());
    }

    /** Reconnect check with exponential backoff, only while a session exists and the streamer was started. */
    @Scheduled(fixedDelay = 5000)
    void reconnectIfNeeded() {
        if (!enabled || broker.sessionState() != BrokerSessionState.CONNECTED) {
            return;
        }
        if (watched.isEmpty()) {
            // the instrument catalog may have been empty at startup (first deploy: sync runs later); retry until it resolves
            resolveWatchlist();
            if (!watched.isEmpty() && stream != null && stream.isConnected()) {
                log.info("Watchlist resolved after startup; subscribing {} instruments", watched.size());
                resubscribe();
            }
        }
        if (stream == null || !stream.isConnected()) {
            log.info("Reconnecting market stream (backoff {} ms)", backoffMs);
            try {
                Thread.sleep(Math.min(backoffMs, 30000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 30000);
            start();
        }
    }

    /** True when a tick has arrived recently enough (or the session is closed, when no ticks are expected). */
    public MarketDataState state() {
        if (!enabled) {
            return new MarketDataState(false, "streaming disabled", false);
        }
        boolean connected = stream != null && stream.isConnected();
        if (!clock.isSessionOpen()) {
            return new MarketDataState(connected, connected ? "session closed; connected" : "session closed; not streaming", false);
        }
        boolean fresh = pipeline.lastTickAt().map(ts -> !clock.now().isAfter(ts.plus(properties.staleAfter()))).orElse(false);
        String detail = fresh ? "streaming" : "no tick for over " + properties.staleAfter().toSeconds() + "s";
        return new MarketDataState(connected && fresh, detail, true);
    }

    /**
     * @param healthy   connection up and (during session) receiving ticks
     * @param required  true only when streaming is enabled and the session is open (otherwise readiness is SKIPPED)
     */
    public record MarketDataState(boolean healthy, String detail, boolean required) {
    }
}
