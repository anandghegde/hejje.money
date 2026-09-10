package money.hejje.execution.internal;

import money.hejje.execution.ExecutionEngine;
import money.hejje.risk.KillSwitchAction;
import money.hejje.risk.KillSwitchActivated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Carries out the destructive part of a kill switch action (the flag itself is set in the risk module). */
@Component
class KillSwitchExecutor {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchExecutor.class);

    private final ExecutionEngine engine;

    private final ExecutorLease lease;

    KillSwitchExecutor(ExecutionEngine engine, ExecutorLease lease) {
        this.engine = engine;
        this.lease = lease;
    }

    @EventListener
    void onActivated(KillSwitchActivated event) {
        if (!lease.isActive()) {
            log.warn("Kill switch {} not carried out here: this instance is a standby (the active executor carries it out)", event.action());
            return;
        }
        try {
            if (event.action() == KillSwitchAction.CANCEL_ALL_OPEN) {
                int cancelled = engine.cancelAllOpen();
                log.warn("Kill switch cancelled {} open orders", cancelled);
            } else if (event.action() == KillSwitchAction.CLOSE_ALL_POSITIONS) {
                int cancelled = engine.cancelAllOpen();
                int closed = engine.closeAllPositions();
                log.warn("Kill switch cancelled {} orders and closed {} positions", cancelled, closed);
            }
        } catch (RuntimeException e) {
            log.error("Kill switch execution failed for action {}: {}", event.action(), e.getMessage());
        }
    }
}
