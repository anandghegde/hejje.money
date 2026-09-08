package money.hejje.market.internal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.Quote;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.MarketProperties;
import money.hejje.market.QuoteSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Last tick per instrument with staleness. Falls back to a REST {@code getQuote} when an instrument was never streamed. */
@Component
public class QuoteCache {

    private static final Logger log = LoggerFactory.getLogger(QuoteCache.class);

    private final BrokerAdapter broker;
    private final HejjeClock clock;
    private final MarketProperties properties;
    private final Map<UUID, MarketTick> latest = new ConcurrentHashMap<>();

    QuoteCache(BrokerAdapter broker, HejjeClock clock, MarketProperties properties) {
        this.broker = broker;
        this.clock = clock;
        this.properties = properties;
    }

    public void accept(MarketTick tick) {
        latest.merge(tick.instrumentId(), tick, (old, fresh) -> fresh.ts().isBefore(old.ts()) ? old : fresh);
    }

    public Optional<MarketTick> lastTick(UUID instrumentId) {
        return Optional.ofNullable(latest.get(instrumentId));
    }

    /** The last price, from the cache or a REST fallback; empty when neither is available. */
    public Optional<java.math.BigDecimal> lastPrice(UUID instrumentId) {
        return snapshot(instrumentId).map(QuoteSnapshot::lastPrice);
    }

    public Optional<QuoteSnapshot> snapshot(UUID instrumentId) {
        MarketTick tick = latest.get(instrumentId);
        if (tick == null) {
            return restFallback(instrumentId);
        }
        return Optional.of(toSnapshot(tick));
    }

    public Map<UUID, QuoteSnapshot> snapshots(Set<UUID> instrumentIds) {
        Map<UUID, QuoteSnapshot> out = new java.util.LinkedHashMap<>();
        Set<UUID> missing = new java.util.LinkedHashSet<>();
        for (UUID id : instrumentIds) {
            MarketTick tick = latest.get(id);
            if (tick != null) {
                out.put(id, toSnapshot(tick));
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            restFallback(missing).forEach(out::put);
        }
        return out;
    }

    private QuoteSnapshot toSnapshot(MarketTick tick) {
        boolean stale = clock.now().isAfter(tick.ts().plus(properties.quoteStaleAfter()));
        return new QuoteSnapshot(tick.instrumentId(), tick.ts(), tick.lastPrice(), tick.bid(), tick.ask(), tick.volume(), tick.oi(), stale);
    }

    private Optional<QuoteSnapshot> restFallback(UUID instrumentId) {
        return restFallback(Set.of(instrumentId)).values().stream().findFirst();
    }

    private Map<UUID, QuoteSnapshot> restFallback(Set<UUID> instrumentIds) {
        Map<UUID, QuoteSnapshot> out = new java.util.LinkedHashMap<>();
        try {
            for (Quote q : broker.getQuote(instrumentIds)) {
                MarketTick tick = new MarketTick(q.instrumentId(), q.ts(), q.lastPrice(), q.bid(), q.ask(), q.volume(), q.oi(), MarketTick.Mode.QUOTE);
                latest.putIfAbsent(q.instrumentId(), tick);
                out.put(q.instrumentId(), toSnapshot(tick));
            }
        } catch (BrokerException e) {
            log.debug("Quote REST fallback failed ({}): {}", e.kind(), e.brokerMessage());
        }
        return out;
    }
}
