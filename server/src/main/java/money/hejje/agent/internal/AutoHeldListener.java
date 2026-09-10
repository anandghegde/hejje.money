package money.hejje.agent.internal;

import money.hejje.agent.ApprovalService;
import money.hejje.auto.AutoExecutionHeld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** AUTO signals the policy held become approvals in the inbox (plan M5.2: approval-required paths still create approvals). */
@Component
class AutoHeldListener {

    private static final Logger log = LoggerFactory.getLogger(AutoHeldListener.class);

    private final ApprovalService approvals;

    AutoHeldListener(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @EventListener
    void onHeld(AutoExecutionHeld event) {
        try {
            approvals.proposeHeldSignal(event.signalId(), event.requestedBy(), event.policy(), event.reason());
        } catch (RuntimeException e) {
            log.warn("Approval for held signal {} failed", event.signalId(), e);
        }
    }
}
