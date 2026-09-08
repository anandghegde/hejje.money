package money.hejje.execution.internal;

import java.util.List;
import java.util.UUID;
import money.hejje.execution.ReconciliationIssue;
import money.hejje.execution.ReconciliationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/execution")
class ExecutionController {

    private final ReconciliationService reconciliation;

    ExecutionController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/reconcile")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    List<ReconciliationIssue> reconcile() {
        return reconciliation.reconcile();
    }

    @GetMapping("/reconciliation-issues")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<ReconciliationIssue> issues() {
        return reconciliation.openIssues();
    }

    @PostMapping("/reconciliation-issues/{id}/resolve")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    ReconciliationIssue resolve(@PathVariable UUID id) {
        return reconciliation.resolve(id);
    }
}
