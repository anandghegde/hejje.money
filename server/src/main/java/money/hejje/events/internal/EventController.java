package money.hejje.events.internal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.events.EventRisk;
import money.hejje.events.EventService;
import money.hejje.events.EventType;
import money.hejje.events.MarketEvent;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
class EventController {

    private final EventService events;

    EventController(EventService events) {
        this.events = events;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<MarketEvent> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) UUID instrumentId,
            @RequestParam(required = false, defaultValue = "false") boolean all) {
        LocalDate start = from == null ? LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")) : from;
        LocalDate end = to == null ? start.plusDays(7) : to;
        return events.events(start, end, instrumentId, all || instrumentId == null && from == null);
    }

    @GetMapping("/risk")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    EventRisk risk(@RequestParam(required = false) UUID instrumentId) {
        return events.risk(instrumentId);
    }

    record AddRequest(EventType type, String symbol, String title, LocalDate date, LocalTime time, LocalDate endDate, Double confidence) {}

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    @ResponseStatus(HttpStatus.CREATED)
    MarketEvent add(@RequestBody AddRequest r, @AuthenticationPrincipal HejjePrincipal principal) {
        if (r.type() == null || r.date() == null || r.title() == null || r.title().isBlank()) {
            throw new IllegalArgumentException("type, title and date are required");
        }
        return events.add(r.type(), r.symbol(), r.title(), r.date(), r.time(), r.endDate(), r.confidence(), principal.name());
    }

    @PostMapping(value = "/import", consumes = {"text/csv", "text/plain"})
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    EventService.ImportResult importCsv(@RequestBody String csv, @AuthenticationPrincipal HejjePrincipal principal) {
        return events.importCsv(csv, principal.name());
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    EventService.RefreshResult refresh(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return from == null || to == null ? events.refresh() : events.refresh(from, to);
    }
}
