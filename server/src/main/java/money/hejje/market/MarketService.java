package money.hejje.market;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.market.internal.MarketCandleStore;
import money.hejje.market.internal.MarketDataStreamer;
import money.hejje.market.internal.QuoteCache;
import org.springframework.stereotype.Service;

/** Public API of the market module: quotes, candles (recent Postgres + older Parquet), and stream subscriptions. */
@Service
public class MarketService {

    private final QuoteCache quotes;
    private final MarketCandleStore recent;
    private final HistoricalCandleStore historical;
    private final MarketDataStreamer streamer;

    MarketService(QuoteCache quotes, MarketCandleStore recent, HistoricalCandleStore historical, MarketDataStreamer streamer) {
        this.quotes = quotes;
        this.recent = recent;
        this.historical = historical;
        this.streamer = streamer;
    }

    public Optional<QuoteSnapshot> quote(UUID instrumentId) {
        return quotes.snapshot(instrumentId);
    }

    public Map<UUID, QuoteSnapshot> quotes(Set<UUID> instrumentIds) {
        return quotes.snapshots(instrumentIds);
    }

    public Optional<java.math.BigDecimal> lastPrice(UUID instrumentId) {
        return quotes.lastPrice(instrumentId);
    }

    /** Candles in {@code [from, to]}, merging recent Postgres rows with older Parquet history (Postgres wins on overlap). */
    public List<Candle> candles(UUID instrumentId, Timeframe timeframe, Instant from, Instant to) {
        TreeMap<Instant, Candle> merged = new TreeMap<>();
        for (Candle candle : historical.read(instrumentId, timeframe, from, to)) {
            merged.put(candle.openTime(), candle);
        }
        for (Candle candle : recent.read(instrumentId, timeframe, from, to)) {
            merged.put(candle.openTime(), candle);
        }
        return List.copyOf(merged.values());
    }

    public void subscribe(Set<UUID> instrumentIds) {
        streamer.subscribe(instrumentIds);
    }

    public void unsubscribe(Set<UUID> instrumentIds) {
        streamer.unsubscribe(instrumentIds);
    }

    public Set<UUID> subscriptions() {
        return streamer.subscriptions();
    }
}
