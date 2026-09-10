package money.hejje.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.orders.OrderReason;

/**
 * Grouped execution (PRD 34, plan M5.3): legs placed one at a time through the normal pipeline, hedge legs first, with
 * partial completion tracked and the rollback policy applied on failure.
 *
 * @param marginRequired  the broker's margin for all legs together (null when the broker could not say)
 */
public record Basket(UUID id, ExecutionMode mode, String name, UUID clientId, ActorType source, String actorId, UUID strategyId, OrderReason reason,
        Policy policy, Rollback rollback, Instant deadline, Status status, Money marginRequired, Money marginAvailable, String detail, Instant createdAt,
        Instant updatedAt, List<BasketLeg> legs) {

    public Basket {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    /** ALL_OR_NOTHING stops at the first failed leg; BEST_EFFORT places every leg regardless. */
    public enum Policy { ALL_OR_NOTHING, BEST_EFFORT }

    /** What an ALL_OR_NOTHING failure does with legs that already filled. */
    public enum Rollback { NONE, CLOSE_FILLED_LEGS }

    public enum Status { PENDING, EXECUTING, COMPLETED, PARTIAL, FAILED, ROLLED_BACK, EXPIRED }
}
