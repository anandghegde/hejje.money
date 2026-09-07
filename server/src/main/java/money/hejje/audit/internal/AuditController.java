package money.hejje.audit.internal;

import java.time.Instant;
import java.util.UUID;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditPage;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAuthority('SCOPE_admin')")
class AuditController {

    private final AuditService audit;

    AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    AuditPage list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) AuditEventType type,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audit.query(new AuditQuery(from, to, type, orderId, page, size));
    }
}
