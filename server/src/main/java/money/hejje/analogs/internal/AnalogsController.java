package money.hejje.analogs.internal;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import money.hejje.analogs.AnalogComputeResult;
import money.hejje.analogs.AnalogMatch;
import money.hejje.analogs.AnalogSummary;
import money.hejje.analogs.AnalogsService;
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
@RequestMapping("/api/v1/analogs")
@PreAuthorize("hasAuthority('SCOPE_market:read')")
class AnalogsController {

    private final AnalogsService analogs;

    AnalogsController(AnalogsService analogs) {
        this.analogs = analogs;
    }

    /** Every endpoint of the module answers 503 while it is switched off. */
    @ModelAttribute
    void requireEnabled() {
        if (!analogs.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Analogs are disabled (hejje.analogs.enabled=false)");
        }
    }

    record ComputeRequest(List<LocalDate> dates, List<String> symbols, List<Integer> lookbacks) {}

    @PostMapping("/compute")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    AnalogComputeResult compute(@RequestBody ComputeRequest request) {
        if (request.dates() == null || request.dates().isEmpty()) {
            throw new IllegalArgumentException("dates are required");
        }
        if (request.lookbacks() != null && !analogs.lookbacks().containsAll(request.lookbacks())) {
            throw new IllegalArgumentException("lookbacks must be among " + analogs.lookbacks());
        }
        Set<String> symbols = new HashSet<>();
        if (request.symbols() != null) {
            request.symbols().forEach(s -> symbols.add(s.trim().toUpperCase()));
        }
        return analogs.computeDaily(request.dates(), symbols, request.lookbacks());
    }

    @GetMapping("/rank")
    Map<String, Object> rank(@RequestParam(defaultValue = "15") int lookback, @RequestParam(defaultValue = "5") int forward,
            @RequestParam(required = false) String sort, @RequestParam(defaultValue = "10") int minCount,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Map.of("date", analogs.latestDaily(date).map(LocalDate::toString).orElse(""), "lookback", lookback, "forward", forward,
                "rows", analogs.rank(lookback, forward, sort, minCount, date));
    }

    /** Session analogs of several symbols in one call (the Today page asks once for all its candidates); symbols without a summary are left out. */
    @GetMapping("/session")
    Map<String, AnalogSummary> sessions(@RequestParam List<String> symbols, @RequestParam(required = false) String checkpoint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (symbols.size() > 50) {
            throw new IllegalArgumentException("at most 50 symbols");
        }
        if (checkpoint != null && !analogs.checkpoints().contains(checkpoint)) {
            throw new IllegalArgumentException("checkpoint must be one of " + analogs.checkpoints());
        }
        Map<String, AnalogSummary> out = new java.util.LinkedHashMap<>();
        for (String symbol : symbols) {
            analogs.session(symbol, checkpoint, date).ifPresent(s -> out.put(s.symbol(), s));
        }
        return out;
    }

    /** Session analogs at a checkpoint (default: the latest that has passed); computed on demand when missing. */
    @GetMapping("/session/{symbol}")
    AnalogSummary session(@PathVariable String symbol, @RequestParam(required = false) String checkpoint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (checkpoint != null && !analogs.checkpoints().contains(checkpoint)) {
            throw new IllegalArgumentException("checkpoint must be one of " + analogs.checkpoints());
        }
        return analogs.session(symbol, checkpoint, date).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No session analogs for " + symbol + ": no checkpoint has passed yet, or the bars are missing"));
    }

    @GetMapping("/session/{symbol}/matches")
    List<AnalogMatch> sessionMatches(@PathVariable String symbol, @RequestParam(required = false) String checkpoint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return analogs.sessionMatches(symbol, checkpoint, date)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No stored session matches for " + symbol));
    }

    @GetMapping("/{symbol}")
    AnalogSummary daily(@PathVariable String symbol, @RequestParam(defaultValue = "15") int lookback,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return analogs.daily(symbol, lookback, date)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No daily analogs for " + symbol + " at lookback " + lookback));
    }

    /** Unordered: the client sorts. There is no rank field on purpose. */
    @GetMapping("/{symbol}/matches")
    List<AnalogMatch> matches(@PathVariable String symbol, @RequestParam(defaultValue = "15") int lookback,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return analogs.dailyMatches(symbol, lookback, date)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No stored matches for " + symbol + " (pruned or never computed)"));
    }
}
