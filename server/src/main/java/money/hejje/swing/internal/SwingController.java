package money.hejje.swing.internal;

import java.util.List;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import money.hejje.execution.ReconciliationIssue;
import money.hejje.swing.SwingBookRow;
import money.hejje.swing.SwingPosition;
import money.hejje.swing.SwingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/swing")
class SwingController {

    private final SwingService swing;
    private final HejjeProperties properties;

    SwingController(SwingService swing, HejjeProperties properties) {
        this.swing = swing;
        this.properties = properties;
    }

    /** The open swing book: entry date, days held, entry, stop, goal, R and unrealized P&L (plan M11.1). */
    @GetMapping("/positions")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<SwingBookRow> positions(@RequestParam(required = false) ExecutionMode mode) {
        return swing.book(mode == null ? properties.mode() : mode);
    }

    /** Closed swing round trips, newest first. */
    @GetMapping("/positions/closed")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<SwingPosition> closed(@RequestParam(required = false) ExecutionMode mode, @RequestParam(defaultValue = "50") int limit) {
        return swing.closed(mode == null ? properties.mode() : mode, Math.max(1, Math.min(limit, 500)));
    }

    record StopRequest(@jakarta.validation.constraints.NotNull money.hejje.common.Price stop, boolean widen) {}

    /**
     * Moves a swing position's stop at the broker (plan M11.2): tighten-only; {@code widen: true} lowers it as a manual,
     * audited action.
     */
    @org.springframework.web.bind.annotation.PutMapping("/positions/{id}/stop")
    @PreAuthorize("hasAuthority('SCOPE_positions:close')")
    money.hejje.execution.PositionGtt moveStop(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody StopRequest body,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key", required = false) String key,
            @org.springframework.security.core.annotation.AuthenticationPrincipal money.hejje.common.security.HejjePrincipal principal) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        try {
            return swing.moveStop(id, body.stop().value(), body.widen(), principal.name());
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // --- overnight risk (plan M11.3) --------------------------------------------------------------------------------------

    @GetMapping("/limits")
    @PreAuthorize("hasAuthority('SCOPE_risk:read')")
    money.hejje.swing.SwingLimits limits() {
        return swing.limits(properties.mode());
    }

    record LimitsRequest(long swingCapitalPaise, int maxOpenPositions, long maxRiskPerPositionPaise,
            @jakarta.validation.constraints.NotNull java.math.BigDecimal gapAllowancePct, long maxOvernightRiskPaise, int maxPositionsPerIndustry,
            boolean blockBeforeEvents, boolean blockSurveillance) {}

    @org.springframework.web.bind.annotation.PutMapping("/limits")
    @PreAuthorize("hasAuthority('SCOPE_risk:write')")
    money.hejje.swing.SwingLimits updateLimits(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody LimitsRequest r,
            @org.springframework.security.core.annotation.AuthenticationPrincipal money.hejje.common.security.HejjePrincipal principal) {
        return swing.updateLimits(new money.hejje.swing.SwingLimits(properties.mode(), money.hejje.common.Money.ofPaise(r.swingCapitalPaise()),
                r.maxOpenPositions(), money.hejje.common.Money.ofPaise(r.maxRiskPerPositionPaise()), r.gapAllowancePct(),
                money.hejje.common.Money.ofPaise(r.maxOvernightRiskPaise()), r.maxPositionsPerIndustry(), r.blockBeforeEvents(), r.blockSurveillance()),
                principal.name());
    }

    /** The swing book's gap-adjusted overnight risk, per position and in total, against the budget. */
    @GetMapping("/risk")
    @PreAuthorize("hasAuthority('SCOPE_risk:read')")
    SwingService.OvernightRisk risk() {
        return swing.overnightRisk(properties.mode());
    }

    record SizeRequest(@jakarta.validation.constraints.NotNull money.hejje.common.Price entry, @jakarta.validation.constraints.NotNull money.hejje.common.Price stop) {}

    /** The largest entry the swing limits allow at a price with a stop (sizing from the gap-adjusted risk budget). */
    @PostMapping("/size")
    @PreAuthorize("hasAuthority('SCOPE_risk:read')")
    money.hejje.swing.SwingRisk.Size size(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody SizeRequest r) {
        return swing.size(properties.mode(), r.entry().value(), r.stop().value());
    }

    record CloseBookRequest(String confirmation) {}

    /** Exits every swing position (each with its GTT); typed confirmation "CLOSE SWING BOOK". The kill switch never does this. */
    @PostMapping("/close-all")
    @PreAuthorize("hasAuthority('SCOPE_positions:close')")
    java.util.Map<String, Object> closeBook(@org.springframework.web.bind.annotation.RequestBody CloseBookRequest body,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key", required = false) String key,
            @org.springframework.security.core.annotation.AuthenticationPrincipal money.hejje.common.security.HejjePrincipal principal) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        return java.util.Map.of("closed", swing.closeBook(properties.mode(), body.confirmation(), principal.name()));
    }

    /** Runs the holdings and GTT reconciliation now (it also runs at startup, before the open and after the close). */
    @PostMapping("/reconcile")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    List<ReconciliationIssue> reconcile() {
        return swing.reconcile();
    }
}
