package money.hejje.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.CorrelationId;

/**
 * Input for {@link AuditService#record(AuditEvent)}. Identity and timestamp are assigned by the service.
 * Build with {@link #of(AuditEventType, ActorType)} and the {@code with*} methods.
 */
public record AuditEvent(
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

    public AuditEvent {
        if (type == null || actorType == null) {
            throw new IllegalArgumentException("Audit event needs a type and an actor type");
        }
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
    }

    public static AuditEvent of(AuditEventType type, ActorType actorType) {
        return new AuditEvent(type, actorType, null, null, null, null, null, null, null, null, Map.of());
    }

    public AuditEvent withActorId(String actorId) {
        return new AuditEvent(type, actorType, actorId, correlationId, strategyId, signalId, orderIntentId, orderId, brokerRef, clientSource, payload);
    }

    public AuditEvent withCorrelationId(CorrelationId correlationId) {
        return new AuditEvent(type, actorType, actorId, correlationId, strategyId, signalId, orderIntentId, orderId, brokerRef, clientSource, payload);
    }

    public AuditEvent withStrategyId(UUID strategyId) {
        return new AuditEvent(type, actorType, actorId, correlationId, strategyId, signalId, orderIntentId, orderId, brokerRef, clientSource, payload);
    }

    public AuditEvent withSignalId(UUID signalId) {
        return new AuditEvent(type, actorType, actorId, correlationId, strategyId, signalId, orderIntentId, orderId, brokerRef, clientSource, payload);
    }

    public AuditEvent withOrderIntentId(UUID orderIntentId) {
        return new AuditEvent(type, actorType, actorId, correlationId, strategyId, signalId, orderIntentId, orderId, brokerRef, clientSource, payload);
    }

    public AuditEvent withOrderId(UUID orderId) {
        return new AuditEvent(type, actorType, actorId, correlationId, strategyId, signalId, orderIntentId, orderId, brokerRef, clientSource, payload);
    }

    public AuditEvent withBrokerRef(String brokerRef) {
        return new AuditEvent(type, actorType, actorId, correlationId, strategyId, signalId, orderIntentId, orderId, brokerRef, clientSource, payload);
    }

    public AuditEvent withClientSource(String clientSource) {
        return new AuditEvent(type, actorType, actorId, correlationId, strategyId, signalId, orderIntentId, orderId, brokerRef, clientSource, payload);
    }

    public AuditEvent withPayload(Map<String, Object> payload) {
        return new AuditEvent(type, actorType, actorId, correlationId, strategyId, signalId, orderIntentId, orderId, brokerRef, clientSource, payload);
    }
}
