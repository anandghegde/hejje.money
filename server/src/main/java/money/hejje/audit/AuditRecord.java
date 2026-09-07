package money.hejje.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.CorrelationId;

/** A stored audit event. */
public record AuditRecord(
        UUID id,
        Instant ts,
        AuditEventType type,
        ActorType actorType,
        String actorId,
        CorrelationId correlationId,
        UUID strategyId,
        UUID signalId,
        UUID orderIntentId,
        UUID orderId,
        String brokerRef,
        String clientSource,
        Map<String, Object> payload) {
}
