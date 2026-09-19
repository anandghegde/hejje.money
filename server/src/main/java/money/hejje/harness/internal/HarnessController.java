package money.hejje.harness.internal;

import java.util.Map;
import java.util.UUID;
import money.hejje.harness.HarnessService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The harness snapshot (plan M7.4): {@code GET /harness/snapshot?bot=&session=} and, in SIM, per session. */
@RestController
class HarnessController {

    private final HarnessService harness;
    private final money.hejje.harness.SimReports reports;

    HarnessController(HarnessService harness, money.hejje.harness.SimReports reports) {
        this.harness = harness;
        this.reports = reports;
    }

    /** Session reports, newest first (plan M7.5); the full reports are also the export format. */
    @GetMapping("/api/v1/sim/reports")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    java.util.List<money.hejje.harness.SimReport> reports(@RequestParam(required = false) String bot, @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "50") int limit) {
        return reports.list(bot, version, limit);
    }

    @GetMapping("/api/v1/sim/reports/{id}")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    money.hejje.harness.SimReport report(@PathVariable UUID id) {
        return reports.find(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                "No report " + id));
    }

    /** Imports reports exported from a SIM instance (where the bot is promoted); idempotent per (session, bot). */
    @org.springframework.web.bind.annotation.PostMapping("/api/v1/sim/reports/import")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Map<String, Object> importReports(@org.springframework.web.bind.annotation.RequestBody java.util.List<money.hejje.harness.SimReport> body) {
        return Map.of("received", body.size(), "imported", reports.importReports(body));
    }

    /** Bots ranked by expectancy net of costs over their session reports in the range (plan M7.5). */
    @GetMapping("/api/v1/sim/leaderboard")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Map<String, Object> leaderboard(@RequestParam(required = false) java.time.LocalDate from, @RequestParam(required = false) java.time.LocalDate to,
            @RequestParam(defaultValue = "false") boolean common) {
        return Map.of("from", String.valueOf(from), "to", String.valueOf(to), "common", common, "minSimSessions", reports.minSimSessions(),
                "rows", reports.leaderboard(from, to, common));
    }

    @GetMapping("/api/v1/harness/snapshot")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Map<String, Object> snapshot(@RequestParam(required = false) UUID bot, @RequestParam(required = false) UUID session) {
        return harness.snapshot(bot, session);
    }

    @GetMapping("/api/v1/sim/sessions/{id}/snapshot")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Map<String, Object> sessionSnapshot(@PathVariable UUID id, @RequestParam(required = false) UUID bot) {
        return harness.snapshot(bot, id);
    }
}
