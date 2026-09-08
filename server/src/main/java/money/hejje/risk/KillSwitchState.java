package money.hejje.risk;

import java.time.Instant;
import money.hejje.common.ExecutionMode;

/** Current kill switch state for a mode. */
public record KillSwitchState(ExecutionMode mode, boolean stopNewOrders, Instant setAt, String setBy, String reason) {

    public static KillSwitchState armed(ExecutionMode mode) {
        return new KillSwitchState(mode, false, null, null, null);
    }
}
