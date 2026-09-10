package money.hejje.execution.internal;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.execution.SplitOrder;
import money.hejje.execution.SplitService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Split orders (PRD 35, plan M5.3): progress and children; cancel stops the remaining children. */
@RestController
@RequestMapping("/api/v1/orders/splits")
class SplitController {

    private final SplitService splits;

    SplitController(SplitService splits) {
        this.splits = splits;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<SplitOrder> list(@RequestParam(defaultValue = "20") int limit) {
        return splits.list(limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Map<String, Object> get(@PathVariable UUID id) {
        SplitOrder s = splits.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No split " + id));
        return Map.of("split", s, "children", splits.children(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_orders:cancel')")
    SplitOrder cancel(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal HejjePrincipal principal) {
        OrderController.requireKey(idempotencyKey);
        try {
            return splits.cancel(id, principal.name());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
