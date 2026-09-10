package money.hejje.analytics.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.analytics.AnalyticsService;
import money.hejje.analytics.PnlBucket;
import money.hejje.analytics.ReviewService;
import money.hejje.analytics.TradeReview;
import money.hejje.common.ExecutionMode;
import money.hejje.common.time.HejjeClock;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class AnalyticsController {

    private final AnalyticsService analytics;
    private final ReviewService reviews;
    private final HejjeClock clock;

    AnalyticsController(AnalyticsService analytics, ReviewService reviews, HejjeClock clock) {
        this.analytics = analytics;
        this.reviews = reviews;
        this.clock = clock;
    }

    @GetMapping("/analytics/pnl")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Map<String, Object> pnl(@RequestParam(defaultValue = "strategy") String groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ExecutionMode mode) {
        ExecutionMode m = mode == null ? analytics.mode() : mode;
        Instant start = (from == null ? clock.today().minusDays(30) : from).atStartOfDay(clock.zone()).toInstant();
        Instant end = (to == null ? clock.today() : to).plusDays(1).atStartOfDay(clock.zone()).toInstant();
        List<PnlBucket> buckets = analytics.pnl(groupBy, m, start, end);
        return Map.of("groupBy", groupBy, "mode", m.name(), "from", start.toString(), "to", end.toString(), "buckets", buckets,
                "summary", analytics.summary(m, start, end));
    }

    record CounterfactualRequest(LocalDate from, LocalDate to, money.hejje.analytics.PerformanceMath.CounterfactualFilter exclude) {}

    private LocalDate[] period(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? clock.today() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (end.isBefore(start) || start.plusDays(366).isBefore(end)) {
            throw new IllegalArgumentException("from must be on or before to, at most a year apart");
        }
        return new LocalDate[] {start, end};
    }

    /** Loss attribution (plan M4.5); the period defaults to month to date. */
    @GetMapping("/analytics/losses")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    money.hejje.analytics.LossReport losses(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) ExecutionMode mode) {
        LocalDate[] p = period(from, to);
        return analytics.losses(mode == null ? analytics.mode() : mode, p[0], p[1]);
    }

    @GetMapping("/analytics/slippage")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    money.hejje.analytics.SlippageReport slippage(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) ExecutionMode mode) {
        LocalDate[] p = period(from, to);
        return analytics.slippage(mode == null ? analytics.mode() : mode, p[0], p[1]);
    }

    @GetMapping("/analytics/adherence")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    money.hejje.analytics.AdherenceReport adherence(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) ExecutionMode mode) {
        LocalDate[] p = period(from, to);
        return analytics.adherence(mode == null ? analytics.mode() : mode, p[0], p[1]);
    }

    /** A SIMULATED counterfactual over the period's actual trades, actual figures alongside (PRD 57). Read-only. */
    @PostMapping("/analytics/counterfactual")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    money.hejje.analytics.CounterfactualReport counterfactual(@RequestBody CounterfactualRequest body) {
        LocalDate[] p = period(body.from(), body.to());
        money.hejje.analytics.PerformanceMath.CounterfactualFilter filter = body.exclude() == null
                ? new money.hejje.analytics.PerformanceMath.CounterfactualFilter(null, null, null, null, null, null, null, null) : body.exclude();
        if (filter.isEmpty()) {
            throw new IllegalArgumentException("exclude needs at least one of regimes, trends, families, strategies, events, news, hours, instruments");
        }
        return analytics.counterfactual(analytics.mode(), p[0], p[1], filter);
    }

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<TradeReview> reviews(@RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) ExecutionMode mode) {
        return analytics.reviews(mode == null ? analytics.mode() : mode, Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping("/reviews/{id}")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    TradeReview review(@PathVariable UUID id) {
        return analytics.review(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No review " + id));
    }

    @GetMapping("/reviews/by-order/{entryOrderId}")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    TradeReview byOrder(@PathVariable UUID entryOrderId) {
        return analytics.reviewForEntryOrder(entryOrderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No review for order " + entryOrderId));
    }

    /** Re-runs the review for a flattened position (admin; the listener normally does this). */
    @PostMapping("/reviews/positions/{positionId}")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    TradeReview reviewNow(@PathVariable UUID positionId) {
        return reviews.reviewClosedPosition(positionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No closed round trip for position " + positionId));
    }
}
