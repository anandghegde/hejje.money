package money.hejje.signals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Side;

/**
 * A position opened from a signal and managed by its runner. Persisted so a restart re-attaches the stop.
 *
 * @param stopOrderId the live broker-side protective stop (SL-M), null while missing
 * @param exitOrderId the market exit order once an exit was triggered
 */
public record StrategyPosition(UUID id, UUID signalId, UUID deploymentId, UUID versionId, UUID strategyId, UUID instrumentId, ExecutionMode mode,
        Side side, int quantity, BigDecimal entryPrice, BigDecimal initialStop, BigDecimal stop, BigDecimal target, UUID entryOrderId, UUID stopOrderId,
        UUID exitOrderId, PositionStatus status, CloseReason closeReason, BigDecimal exitPrice, Instant openedAt, Instant closedAt, Instant updatedAt) {
}
