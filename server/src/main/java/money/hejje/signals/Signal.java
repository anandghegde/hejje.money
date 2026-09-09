package money.hejje.signals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Side;

/**
 * An entry signal produced by a strategy runner at a bar close.
 *
 * @param referencePrice the signal bar's close
 * @param riskPerUnit    |referencePrice − stop|
 * @param barTime        close time of the signal bar
 * @param validUntil     after this the signal expires (next bar close, or {@code signal_validity_minutes})
 * @param evidence       every entry condition with its observed values
 */
public record Signal(UUID id, UUID versionId, UUID strategyId, UUID deploymentId, UUID instrumentId, ExecutionMode mode, Side side,
        BigDecimal referencePrice, BigDecimal stop, BigDecimal target, BigDecimal riskPerUnit, Instant barTime, Instant validUntil,
        List<Map<String, Object>> evidence, SignalStatus status, String note, UUID intentId, UUID orderId, Instant createdAt, Instant updatedAt) {

    public Signal {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public Signal with(SignalStatus status, String note, UUID intentId, UUID orderId, Instant at) {
        return new Signal(id, versionId, strategyId, deploymentId, instrumentId, mode, side, referencePrice, stop, target, riskPerUnit, barTime, validUntil,
                evidence, status, note, intentId == null ? this.intentId : intentId, orderId == null ? this.orderId : orderId, createdAt, at);
    }

    public boolean isExpiredAt(Instant now) {
        return status.isActionable() && !now.isBefore(validUntil);
    }
}
