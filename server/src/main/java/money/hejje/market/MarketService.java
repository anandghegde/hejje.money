package money.hejje.market;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.common.Timeframe;
import java.time.LocalDate;
import money.hejje.market.internal.ContinuousFuturesBuilder;
import money.hejje.market.internal.ContinuousSeriesStore;
import money.hejje.market.internal.HistoryIntegrity;
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
    private final ContinuousSeriesStore continuous;
    private final ContinuousFuturesBuilder continuousBuilder;
    private final money.hejje.common.time.HejjeClock clock;

    MarketService(QuoteCache quotes, MarketCandleStore recent, HistoricalCandleStore historical, MarketDataStreamer streamer,
            ContinuousSeriesStore continuous, ContinuousFuturesBuilder continuousBuilder, money.hejje.common.time.HejjeClock clock) {
        this.quotes = quotes;
        this.recent = recent;
        this.historical = historical;
        this.streamer = streamer;
        this.continuous = continuous;
        this.continuousBuilder = continuousBuilder;
        this.clock = clock;
    }

    // --- continuous futures series (docs/data.md) ---

    public Optional<ContinuousSeries> continuousSeries(UUID id) {
        return continuous.findById(id);
    }

    public Optional<ContinuousSeries> continuousSeriesBySymbol(String symbol) {
        return continuous.findBySymbol(symbol.trim().toUpperCase());
    }

    /** The series for an underlying regardless of exchange (there is one per underlying). */
    public Optional<ContinuousSeries> continuousSeriesFor(String underlying) {
        String u = underlying.trim().toUpperCase();
        return continuous.findAll().stream().filter(s -> s.underlying().equals(u)).findFirst();
    }

    public List<ContinuousSeries> continuousSeries() {
        return continuous.findAll();
    }

    /** Builds or rebuilds the continuous series for {@code underlying} from the contracts in the historical store. */
    public ContinuousSeries buildContinuousSeries(String underlying, Timeframe timeframe) {
        return continuousBuilder.build(underlying, timeframe);
    }

    /** Store contents versus the holiday calendar for {@code [from, to]} sessions (IST). */
    public DataIntegrityReport integrity(UUID instrumentId, Timeframe timeframe, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(clock.zone()).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(clock.zone()).toInstant().minusSeconds(1);
        return HistoryIntegrity.report(instrumentId, timeframe, from, to, candles(instrumentId, timeframe, start, end), clock);
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

    /** Streams the instruments in FULL mode (volume and open interest), e.g. an option chain (M5.4). */
    public void subscribeFull(Set<UUID> instrumentIds) {
        streamer.subscribeFull(instrumentIds);
    }

    public void unsubscribe(Set<UUID> instrumentIds) {
        streamer.unsubscribe(instrumentIds);
    }

    public Set<UUID> subscriptions() {
        return streamer.subscriptions();
    }
}
