package money.hejje.market.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.market.Candle;
import money.hejje.market.CandleCoverage;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.market.MarketService;
import money.hejje.market.QuoteSnapshot;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/market")
@PreAuthorize("hasAuthority('SCOPE_market:read')")
class MarketController {

    private final MarketService market;
    private final HistoricalCandleStore historical;
    private final HistoricalBackfillJob backfill;
    private final money.hejje.market.MarketProperties properties;
    private final MarketPipeline pipeline;
    private final money.hejje.common.event.TickBus bus;
    private final money.hejje.common.time.HejjeClock clock;
    private final money.hejje.market.UniverseHistory universeHistory;

    MarketController(MarketService market, HistoricalCandleStore historical, HistoricalBackfillJob backfill, money.hejje.market.MarketProperties properties,
            MarketPipeline pipeline, money.hejje.common.event.TickBus bus, money.hejje.common.time.HejjeClock clock,
            money.hejje.market.UniverseHistory universeHistory) {
        this.universeHistory = universeHistory;
        this.market = market;
        this.historical = historical;
        this.backfill = backfill;
        this.properties = properties;
        this.pipeline = pipeline;
        this.bus = bus;
        this.clock = clock;
    }

    /** One candle to seed; times are ISO instants. */
    record DevCandle(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant openTime, java.math.BigDecimal open, java.math.BigDecimal high,
            java.math.BigDecimal low, java.math.BigDecimal close, long volume) {}

    /**
     * @param store   write the candles to the historical store (backtests, warm-up)
     * @param publish publish each candle as a closed candle on the tick bus (signal runners)
     * @param quote   feed a tick (at the close price, stamped now) into the pipeline (quote cache, readiness)
     */
    record DevCandlesRequest(UUID instrumentId, Timeframe timeframe, List<DevCandle> candles, Boolean store, Boolean publish, Boolean quote) {}

    /**
     * Development seeding (docs/data.md): only when {@code hejje.market.dev-candles=true} (dev and test profiles). Lets a
     * replayed or scripted session drive backtests, runners and the Today screen without a broker.
     */
    @PostMapping("/dev/candles")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Map<String, Object> devCandles(@RequestBody DevCandlesRequest request) {
        if (!properties.devCandles()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dev candle seeding is disabled");
        }
        Timeframe tf = request.timeframe() == null ? Timeframe.M5 : request.timeframe();
        List<Candle> candles = request.candles().stream().map(c -> new Candle(request.instrumentId(), tf, c.openTime(), c.open(), c.high(), c.low(), c.close(),
                c.volume(), 0, false)).toList();
        if (!Boolean.FALSE.equals(request.store())) {
            historical.write(request.instrumentId(), tf, candles);
        }
        if (Boolean.TRUE.equals(request.publish())) {
            for (Candle candle : candles) {
                if (Boolean.TRUE.equals(request.quote())) {
                    // stamped "now" so the quote cache and market-data readiness treat it as fresh whatever the candle's date
                    pipeline.onTick(new money.hejje.common.event.MarketTick(request.instrumentId(), clock.now(), candle.close(), null, null,
                            candle.volume(), 0, money.hejje.common.event.MarketTick.Mode.LTP));
                }
                bus.publish(new money.hejje.market.CandleClosedEvent(candle));
            }
        }
        return Map.of("instrumentId", request.instrumentId(), "timeframe", tf.name(), "candles", candles.size());
    }

    record Subscriptions(Set<UUID> instrumentIds) {}

    @GetMapping("/quotes")
    Map<UUID, QuoteSnapshot> quotes(@RequestParam("ids") Set<UUID> ids) {
        return market.quotes(ids);
    }

    @GetMapping("/candles")
    List<Candle> candles(@RequestParam UUID instrumentId, @RequestParam Timeframe timeframe,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return market.candles(instrumentId, timeframe, from, to);
    }

    @PostMapping("/subscriptions")
    Map<String, Object> subscribe(@RequestBody Subscriptions body) {
        market.subscribe(body.instrumentIds());
        return Map.of("subscribed", market.subscriptions());
    }

    @DeleteMapping("/subscriptions")
    Map<String, Object> unsubscribe(@RequestBody Subscriptions body) {
        market.unsubscribe(body.instrumentIds());
        return Map.of("subscribed", market.subscriptions());
    }

    @PostMapping("/history/backfill")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Map<String, Object> backfill(@RequestBody BackfillRequest request) {
        UUID jobId = backfill.start(request.instrumentId(), request.timeframe(), request.from(), request.to());
        return Map.of("jobId", jobId);
    }

    record BackfillRequest(UUID instrumentId, Timeframe timeframe,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {}

    record UniverseBackfillRequest(String universe, Timeframe timeframe,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {}

    /** One parent job over a backfill per instrument of a universe file; symbols the master lacks are reported. */
    @PostMapping("/history/backfill-universe")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Map<String, Object> backfillUniverse(@RequestBody UniverseBackfillRequest request) {
        if (request.universe() == null || request.from() == null || request.to() == null || !request.from().isBefore(request.to())) {
            throw new IllegalArgumentException("universe, from and to (from before to) are required");
        }
        UUID jobId = universeHistory.backfill(request.universe(), request.timeframe() == null ? Timeframe.D1 : request.timeframe(),
                request.from(), request.to());
        money.hejje.market.UniverseBackfill started = universeHistory.progress(jobId);
        return Map.of("jobId", jobId, "children", started.childrenTotal(), "unresolved", started.unresolved());
    }

    @GetMapping("/history/jobs/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Object job(@PathVariable UUID id) {
        Object progress = backfill.progress(id);
        if (progress == null) {
            progress = backfill.universeProgress(id);
        }
        if (progress == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No backfill job " + id);
        }
        return progress;
    }

    @GetMapping("/history/coverage")
    CandleCoverage coverage(@RequestParam UUID instrumentId, @RequestParam(defaultValue = "M1") Timeframe timeframe) {
        return historical.coverage(instrumentId, timeframe);
    }

    @GetMapping("/history/integrity")
    money.hejje.market.DataIntegrityReport integrity(@RequestParam UUID instrumentId, @RequestParam(defaultValue = "M5") Timeframe timeframe,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return market.integrity(instrumentId, timeframe, from, to);
    }

    record ContinuousRequest(String underlying, Timeframe timeframe) {}

    @PostMapping("/history/continuous")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    money.hejje.market.ContinuousSeries buildContinuous(@RequestBody ContinuousRequest request) {
        if (request.underlying() == null || request.underlying().isBlank()) {
            throw new IllegalArgumentException("underlying is required");
        }
        return market.buildContinuousSeries(request.underlying(), request.timeframe() == null ? Timeframe.M5 : request.timeframe());
    }

    @GetMapping("/history/continuous")
    List<money.hejje.market.ContinuousSeries> continuousSeries(@RequestParam(required = false) String underlying) {
        return underlying == null ? market.continuousSeries() : market.continuousSeriesFor(underlying).map(List::of).orElse(List.of());
    }
}
