package money.hejje.auto;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;
import money.hejje.risk.policy.PolicyResult;

/** An AUTO signal the policy sent to a human (plan M5.2); the agent module turns it into an approval in the inbox. */
public record AutoExecutionHeld(EventMeta meta, UUID signalId, UUID deploymentId, UUID strategyId, String requestedBy, String reason, PolicyResult policy)
        implements HejjeEvent {
    public UUID id() { return meta.id(); }
    public Instant occurredAt() { return meta.occurredAt(); }
    public CorrelationId correlationId() { return meta.correlationId(); }
}
