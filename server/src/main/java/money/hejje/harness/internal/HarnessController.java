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

    HarnessController(HarnessService harness) {
        this.harness = harness;
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
