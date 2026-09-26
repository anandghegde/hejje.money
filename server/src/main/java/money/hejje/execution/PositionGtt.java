package money.hejje.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.ExecutionMode;

/**
 * The broker-side stop of one delivery position (plan M11.2): an OCO GTT (stop and goal) or a single stop GTT.
 *
 * @param status      ACTIVE (placed and, once reconciled, confirmed at the broker); MISSING (the broker no longer has it: an
 *                    incident that blocks new swing entries); TRIGGERED (it fired); CANCELLED (closed with its position or
 *                    replaced)
 * @param confirmedAt the last time the broker listed it as active, or null
 */
public record PositionGtt(UUID id, ExecutionMode mode, String broker, String brokerGttId, UUID positionId, UUID instrumentId, int quantity,
        BigDecimal stop, BigDecimal goal, Status status, String triggeredOrderId, Instant createdAt, Instant updatedAt, Instant confirmedAt) {

    public enum Status { ACTIVE, MISSING, TRIGGERED, CANCELLED }
}
