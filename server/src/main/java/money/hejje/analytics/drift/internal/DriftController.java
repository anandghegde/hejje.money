package money.hejje.analytics.drift.internal;

import java.util.Map;
import java.util.UUID;
import money.hejje.analytics.drift.DriftService;
import money.hejje.analytics.drift.DriftState;
import money.hejje.common.security.HejjePrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class DriftController {

    private final DriftService drift;

    DriftController(DriftService drift) {
        this.drift = drift;
    }

    @GetMapping("/strategies/{id}/drift")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    Map<String, Object> drift(@PathVariable UUID id) {
        return Map.of("strategyId", id, "enabled", drift.enabled(), "deployments", drift.forStrategy(id));
    }

    record OverrideRequest(String reason) {}

    @PostMapping("/deployments/{id}/drift/override")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    DriftState override(@PathVariable UUID id, @RequestBody OverrideRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        return drift.override(id, body.reason(), principal.name());
    }
}
