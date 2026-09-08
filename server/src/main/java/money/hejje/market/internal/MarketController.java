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

    MarketController(MarketService market, HistoricalCandleStore historical, HistoricalBackfillJob backfill) {
        this.market = market;
        this.historical = historical;
        this.backfill = backfill;
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

    @GetMapping("/history/jobs/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    HistoricalBackfillJob.Progress job(@PathVariable UUID id) {
        HistoricalBackfillJob.Progress progress = backfill.progress(id);
        if (progress == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No backfill job " + id);
        }
        return progress;
    }

    @GetMapping("/history/coverage")
    CandleCoverage coverage(@RequestParam UUID instrumentId, @RequestParam(defaultValue = "M1") Timeframe timeframe) {
        return historical.coverage(instrumentId, timeframe);
    }
}
