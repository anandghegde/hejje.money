package money.hejje.ratings.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import money.hejje.market.UniverseBackfill;
import money.hejje.ratings.Base;
import money.hejje.ratings.BaseStatus;
import money.hejje.ratings.BaseType;
import money.hejje.ratings.BasesComputeResult;
import money.hejje.ratings.DailyRating;
import money.hejje.ratings.GroupRank;
import money.hejje.ratings.RatingsComputeResult;
import money.hejje.ratings.RatingsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/ratings")
@PreAuthorize("hasAuthority('SCOPE_market:read')")
class RatingsController {

    private final RatingsService ratings;
    private final DailyRefresh refresh;

    RatingsController(RatingsService ratings, DailyRefresh refresh) {
        this.ratings = ratings;
        this.refresh = refresh;
    }

    /** Every endpoint of the module answers 503 while it is switched off. */
    @ModelAttribute
    void requireEnabled() {
        if (!ratings.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ratings are disabled (hejje.ratings.enabled=false)");
        }
    }

    record Range(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {}

    @PostMapping("/compute")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    RatingsComputeResult compute(@RequestBody Range range) {
        if (range.from() == null || range.to() == null || range.from().isAfter(range.to())) {
            throw new IllegalArgumentException("from and to (from not after to) are required");
        }
        return ratings.compute(range.from(), range.to());
    }

    record RefreshRequest(Integer sessions) {}

    /** Runs the evening D1 refresh now, over more sessions than the nightly default when the universe is stale. */
    @PostMapping("/refresh")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    UniverseBackfill refresh(@RequestBody(required = false) RefreshRequest request) {
        int sessions = request == null || request.sessions() == null ? 5 : request.sessions();
        if (sessions < 1 || sessions > 2000) {
            throw new IllegalArgumentException("sessions must be 1..2000");
        }
        return refresh.refresh(sessions);
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String sort, @RequestParam(required = false) Integer minRs,
            @RequestParam(required = false) String group, @RequestParam(defaultValue = "50") int limit) {
        List<DailyRating> all = ratings.ratings(date);
        List<DailyRating> rows = all.stream()
                .filter(r -> minRs == null || (r.rsRating() != null && r.rsRating() >= minRs))
                .filter(r -> group == null || group.equals(r.groupId()))
                .sorted(RatingsService.order(sort)).limit(Math.max(1, Math.min(limit, 1000))).toList();
        return Map.of("date", all.isEmpty() ? "" : all.get(0).sessionDate().toString(), "universe", all.size(), "ratings", rows);
    }

    @PostMapping("/bases/compute")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    BasesComputeResult computeBases(@RequestBody Range range) {
        if (range.from() == null || range.to() == null || range.from().isAfter(range.to())) {
            throw new IllegalArgumentException("from and to (from not after to) are required");
        }
        return ratings.computeBases(range.from(), range.to());
    }

    @GetMapping("/bases")
    List<Base> bases(@RequestParam(required = false) BaseStatus status, @RequestParam(required = false) BaseType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ratings.bases(status, type, date);
    }

    /** The past-setups ledger with hit-goal / stopped / failed / expired counts and the mean outcome in R per type. */
    @GetMapping("/setups/past")
    Map<String, Object> pastSetups(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<Base> closed = ratings.pastSetups(from, to);
        Map<String, Map<String, Object>> summary = new java.util.TreeMap<>();
        for (Base b : closed) {
            Map<String, Object> row = summary.computeIfAbsent(b.type().name(), t -> new java.util.LinkedHashMap<>(Map.of("count", 0)));
            row.merge("count", 1, (a, c) -> (Integer) a + 1);
            row.merge(b.status().name(), 1, (a, c) -> (Integer) a + 1);
            if (b.outcomeR() != null) {
                row.merge("triggered", 1, (a, c) -> (Integer) a + 1);
                row.merge("sumR", b.outcomeR(), (a, c) -> (Double) a + (Double) c);
            }
        }
        summary.values().forEach(row -> {
            Double sum = (Double) row.remove("sumR");
            row.put("meanR", sum == null ? null : Math.round(sum / (Integer) row.get("triggered") * 100.0) / 100.0);
        });
        return Map.of("from", from.toString(), "to", to.toString(), "summary", summary, "setups", closed);
    }

    @GetMapping("/lists/{name}")
    Map<String, Object> list(@PathVariable String name, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String session = ratings.sessionFor(date).map(LocalDate::toString).orElse("");
        if (name.equals("groups")) {
            return Map.of("name", name, "date", session, "groups", ratings.groups(date));
        }
        return Map.of("name", name, "date", session, "rows", ratings.list(name, date));
    }

    @GetMapping("/{symbol}/bases")
    List<Base> basesOf(@PathVariable String symbol, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ratings.basesOf(symbol, date);
    }

    @GetMapping("/groups")
    List<GroupRank> groups(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ratings.groups(date);
    }

    @GetMapping("/{symbol}")
    DailyRating one(@PathVariable String symbol, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ratings.rating(symbol, date).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No rating for " + symbol));
    }

    @GetMapping("/{symbol}/history")
    List<DailyRating> history(@PathVariable String symbol, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ratings.history(symbol, from, to);
    }
}
