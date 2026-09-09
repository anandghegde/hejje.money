package money.hejje.context.internal;

import java.util.UUID;
import money.hejje.context.ContextService;
import money.hejje.context.StrategyContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/context")
class ContextController {

    private final ContextService context;

    ContextController(ContextService context) {
        this.context = context;
    }

    @GetMapping("/strategy")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    StrategyContext strategy(@RequestParam UUID versionId, @RequestParam(required = false) UUID instrumentId) {
        return context.strategyContext(versionId, instrumentId);
    }
}
