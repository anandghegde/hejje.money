package money.hejje.execution.internal;

import money.hejje.execution.ReconciliationService;
import money.hejje.system.ReadinessCheck;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Readiness line {@code reconciliation}: BLOCKING while an open CRITICAL issue exists. */
@Component
class ReconciliationReadiness implements ReadinessCheck {

    private final ReconciliationService reconciliation;

    ReconciliationReadiness(@Lazy ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Override
    public String name() {
        return "reconciliation";
    }

    @Override
    public CheckResult result() {
        return reconciliation.hasCriticalOpen()
                ? CheckResult.blocking("unresolved CRITICAL reconciliation issue")
                : CheckResult.ok("reconciled");
    }
}
