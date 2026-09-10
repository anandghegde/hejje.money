package money.hejje.execution.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.execution.ExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Active/standby status and controlled failover (PRD 43, plan M5.6). */
@RestController
@RequestMapping("/api/v1/server")
class ExecutorController {

    static final String CONFIRMATION = "FAILOVER";

    private final ExecutorLease lease;
    private final AuditService audit;

    ExecutorController(ExecutorLease lease, AuditService audit) {
        this.lease = lease;
        this.audit = audit;
    }

    @GetMapping("/executor")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    ExecutorLease.Status executor() {
        return lease.status();
    }

    record FailoverRequest(String confirmation) {}

    /** On the active instance: release the lease so the standby takes over; this instance stands by for the failover hold. */
    @PostMapping("/failover")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    ResponseEntity<?> failover(@RequestBody(required = false) FailoverRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        if (body == null || !CONFIRMATION.equals(body.confirmation())) {
            return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Controlled failover releases this instance's executor lease; send {\"confirmation\": \"FAILOVER\"}"));
        }
        long epoch = lease.epoch();
        Instant holdUntil;
        try {
            holdUntil = lease.failover();
        } catch (ExecutionException.NotActiveExecutor e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    e.getMessage() + "; run the failover on the active instance"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instance", lease.instance());
        payload.put("epoch", epoch);
        payload.put("holdUntil", holdUntil.toString());
        audit.record(AuditEvent.of(AuditEventType.EXECUTOR_FAILOVER, ActorType.USER).withActorId(principal.name()).withPayload(payload));
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("released", true);
        out.put("message", "Executor lease released; a standby takes over on its next heartbeat. This instance stays a standby at least until "
                + holdUntil + ".");
        return ResponseEntity.ok(out);
    }
}
