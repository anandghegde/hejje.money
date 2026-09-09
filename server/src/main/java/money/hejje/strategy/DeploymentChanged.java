package money.hejje.strategy;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

/** Durable event: a deployment was created, enabled or paused. The signal engine (M2.6) reacts to it. */
public record DeploymentChanged(EventMeta meta, UUID deploymentId, UUID versionId, boolean enabled) implements HejjeEvent {
    public UUID id() { return meta.id(); }
    public Instant occurredAt() { return meta.occurredAt(); }
    public CorrelationId correlationId() { return meta.correlationId(); }
}
