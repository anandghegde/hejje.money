package money.hejje.agent.internal;

import java.util.List;
import java.util.UUID;
import money.hejje.agent.Approval;
import money.hejje.agent.ApprovalException;
import money.hejje.agent.ApprovalService;
import money.hejje.agent.ApprovalStatus;
import money.hejje.common.security.HejjePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Approvals inbox (plan M4.4): {@code orders:execute}, i.e. a human's credentials (no agent preset carries it). Approve
 * and reject need an {@code Idempotency-Key}; approving re-runs policy and risk, then the normal pipeline.
 */
@RestController
@RequestMapping("/api/v1/approvals")
@PreAuthorize("hasAuthority('SCOPE_orders:execute')")
class ApprovalController {

    record RejectRequest(String reason) {}

    private final ApprovalService approvals;

    ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    List<Approval> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        ApprovalStatus s = status == null || status.isBlank() || status.equalsIgnoreCase("ALL") ? null : ApprovalStatus.valueOf(status.toUpperCase());
        return approvals.list(s, Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/{id}")
    Approval get(@PathVariable UUID id) {
        return approvals.find(id).orElseThrow(() -> new ApprovalException.NotFound(id));
    }

    @PostMapping("/{id}/approve")
    Approval approve(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal HejjePrincipal principal) {
        return approvals.approve(id, key, principal);
    }

    @PostMapping("/{id}/reject")
    Approval reject(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) RejectRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        return approvals.reject(id, body == null ? null : body.reason(), key, principal);
    }

    @ExceptionHandler(ApprovalException.class)
    ProblemDetail handle(ApprovalException e) {
        HttpStatus status = switch (e) {
            case ApprovalException.NotFound n -> HttpStatus.NOT_FOUND;
            case ApprovalException.Conflict c -> HttpStatus.CONFLICT;
            case ApprovalException.Forbidden f -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle("Approval " + status.getReasonPhrase().toLowerCase());
        problem.setProperty("errors", e.reasons());
        return problem;
    }
}
