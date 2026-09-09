package money.hejje.strategy;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

/** Durable event: a version moved through the lifecycle. */
public record StrategyVersionStatusChanged(EventMeta meta, UUID strategyId, UUID versionId, VersionStatus from, VersionStatus to)
        implements HejjeEvent {
    public UUID id() { return meta.id(); }
    public Instant occurredAt() { return meta.occurredAt(); }
    public CorrelationId correlationId() { return meta.correlationId(); }
}
