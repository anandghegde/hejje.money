package money.hejje.agent.internal;

import money.hejje.agent.ApprovalService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks approvals past their expiry EXPIRED (also done lazily whenever approvals are listed or decided). */
@Component
class ApprovalExpiry {

    private final ApprovalService approvals;

    ApprovalExpiry(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @Scheduled(fixedDelayString = "${hejje.agent.approvals.expiry-sweep:30s}")
    void sweep() {
        approvals.expireDue();
    }
}
