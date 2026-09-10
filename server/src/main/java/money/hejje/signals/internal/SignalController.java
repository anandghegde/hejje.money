package money.hejje.signals.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.orders.HejjeOrder;
import money.hejje.signals.PreparedOrder;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalException;
import money.hejje.signals.SignalService;
import money.hejje.signals.SignalStatus;
import money.hejje.signals.StrategyPosition;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/signals")
class SignalController {

    private final SignalService signals;
    private final SignalMetrics metrics;

    SignalController(SignalService signals, SignalMetrics metrics) {
        this.signals = signals;
        this.metrics = metrics;
    }

    record SkipRequest(String reason) {}

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<Signal> list(@RequestParam(required = false) SignalStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(defaultValue = "100") int limit) {
        return signals.list(status, from, limit);
    }

    @GetMapping("/active")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<Signal> active() {
        return signals.active();
    }

    @GetMapping("/positions")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<StrategyPosition> positions(@RequestParam(defaultValue = "true") boolean live) {
        return signals.positions(live);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    Signal get(@PathVariable UUID id) {
        return signals.find(id).orElseThrow(() -> new SignalException.NotFound("Signal " + id + " not found"));
    }

    @PostMapping("/{id}/prepare")
    @PreAuthorize("hasAuthority('SCOPE_orders:prepare')")
    PreparedOrder prepare(@PathVariable UUID id, @AuthenticationPrincipal HejjePrincipal principal) {
        return signals.prepare(id, principal);
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize("hasAuthority('SCOPE_orders:execute')")
    @ResponseStatus(HttpStatus.CREATED)
    Object execute(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal HejjePrincipal principal) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (signals.isOptions(id)) {
            return signals.executeOptions(id, idempotencyKey, principal); // options legs (M5.4): the options position
        }
        HejjeOrder order = signals.execute(id, idempotencyKey, principal);
        metrics.executed(order.id(), order.placedAt() == null ? Instant.now() : order.placedAt());
        return order;
    }

    @PostMapping("/{id}/skip")
    @PreAuthorize("hasAuthority('SCOPE_orders:prepare')")
    Signal skip(@PathVariable UUID id, @RequestBody(required = false) SkipRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        return signals.skip(id, body == null ? null : body.reason(), principal);
    }
}
