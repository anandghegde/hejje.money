package money.hejje.orders;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Order lifecycle states, exactly PRD section 36. */
public enum OrderState {
    CREATED,
    VALIDATING,
    RISK_REJECTED,
    READY,
    SUBMITTING,
    BROKER_ACCEPTED,
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    MODIFY_PENDING,
    CANCEL_PENDING,
    CANCELLED,
    REJECTED,
    UNKNOWN,
    RECONCILING;

    /** Terminal states never transition again. */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED || this == RISK_REJECTED;
    }

    /** True while the order may still change at the broker (used by the poll fallback). */
    public boolean isLive() {
        return this == SUBMITTING || this == BROKER_ACCEPTED || this == OPEN || this == PARTIALLY_FILLED
                || this == MODIFY_PENDING || this == CANCEL_PENDING || this == UNKNOWN || this == RECONCILING;
    }

    static final Map<OrderState, Set<OrderState>> TRANSITIONS = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(VALIDATING)),
            Map.entry(VALIDATING, EnumSet.of(READY, RISK_REJECTED, REJECTED)),
            Map.entry(RISK_REJECTED, EnumSet.noneOf(OrderState.class)),
            Map.entry(READY, EnumSet.of(SUBMITTING, REJECTED)),
            Map.entry(SUBMITTING, EnumSet.of(BROKER_ACCEPTED, OPEN, PARTIALLY_FILLED, FILLED, REJECTED, CANCELLED, UNKNOWN)),
            Map.entry(BROKER_ACCEPTED, EnumSet.of(OPEN, PARTIALLY_FILLED, FILLED, MODIFY_PENDING, CANCEL_PENDING, CANCELLED, REJECTED)),
            Map.entry(OPEN, EnumSet.of(PARTIALLY_FILLED, FILLED, MODIFY_PENDING, CANCEL_PENDING, CANCELLED, REJECTED)),
            Map.entry(PARTIALLY_FILLED, EnumSet.of(PARTIALLY_FILLED, FILLED, MODIFY_PENDING, CANCEL_PENDING, CANCELLED, REJECTED)),
            Map.entry(MODIFY_PENDING, EnumSet.of(OPEN, PARTIALLY_FILLED, FILLED, CANCEL_PENDING, CANCELLED, REJECTED)),
            Map.entry(CANCEL_PENDING, EnumSet.of(OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED)),
            Map.entry(FILLED, EnumSet.noneOf(OrderState.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(OrderState.class)),
            Map.entry(REJECTED, EnumSet.noneOf(OrderState.class)),
            Map.entry(UNKNOWN, EnumSet.of(OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED, RECONCILING)),
            Map.entry(RECONCILING, EnumSet.of(OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED)));

    public boolean canTransitionTo(OrderState next) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }
}
