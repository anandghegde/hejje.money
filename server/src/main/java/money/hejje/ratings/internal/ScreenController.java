package money.hejje.ratings.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.ratings.RatingsService;
import money.hejje.ratings.SavedScreen;
import money.hejje.ratings.ScreenRequest;
import money.hejje.ratings.ScreenerService;
import money.hejje.ratings.WatchlistItem;
import money.hejje.ratings.WatchlistService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Screener, saved screens and the watchlist: {@code market:read} to run and read, {@code strategies:write} to save. */
@RestController
@RequestMapping("/api/v1/ratings")
@PreAuthorize("hasAuthority('SCOPE_market:read')")
class ScreenController {

    private final RatingsService ratings;
    private final ScreenerService screener;
    private final WatchlistService watchlist;

    ScreenController(RatingsService ratings, ScreenerService screener, WatchlistService watchlist) {
        this.ratings = ratings;
        this.screener = screener;
        this.watchlist = watchlist;
    }

    @ModelAttribute
    void requireEnabled() {
        if (!ratings.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ratings are disabled (hejje.ratings.enabled=false)");
        }
    }

    @PostMapping("/screen")
    ScreenerService.Result run(@RequestBody ScreenRequest request) {
        return screener.run(request);
    }

    @GetMapping("/screen/fields")
    List<String> fields() {
        return screener.fields();
    }

    @GetMapping("/screens")
    List<SavedScreen> screens() {
        return screener.screens();
    }

    /** Runs a saved screen on the latest session (or {@code date}). */
    @PostMapping("/screens/{id}/run")
    ScreenerService.Result runSaved(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        SavedScreen saved = screener.screen(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No screen " + id));
        java.time.LocalDate date = body == null || body.get("date") == null ? null : java.time.LocalDate.parse(body.get("date"));
        ScreenRequest d = saved.definition();
        return screener.run(new ScreenRequest(date, d.filters(), d.sort(), d.limit()));
    }

    record SaveScreen(String name, ScreenRequest definition) {}

    @PostMapping("/screens")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    SavedScreen save(@RequestBody SaveScreen body, @AuthenticationPrincipal HejjePrincipal principal) {
        if (body.definition() == null) {
            throw new IllegalArgumentException("definition is required");
        }
        return screener.save(body.name(), body.definition(), principal.name());
    }

    @DeleteMapping("/screens/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    Map<String, Object> delete(@PathVariable UUID id, @AuthenticationPrincipal HejjePrincipal principal) {
        if (!screener.delete(id, principal.name())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No screen " + id);
        }
        return Map.of("deleted", id);
    }

    @GetMapping("/watchlist")
    List<WatchlistItem> watchlist() {
        return watchlist.items();
    }

    record WatchRequest(String symbol, String note) {}

    @PostMapping("/watchlist")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    WatchlistItem watch(@RequestBody WatchRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        return watchlist.add(body.symbol(), body.note(), principal.name());
    }

    @DeleteMapping("/watchlist/{symbol}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    Map<String, Object> unwatch(@PathVariable String symbol, @AuthenticationPrincipal HejjePrincipal principal) {
        if (!watchlist.remove(symbol, principal.name())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, symbol + " is not on the watchlist");
        }
        return Map.of("removed", symbol.trim().toUpperCase());
    }
}
