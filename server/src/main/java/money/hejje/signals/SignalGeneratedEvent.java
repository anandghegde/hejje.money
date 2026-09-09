package money.hejje.signals;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

/** Durable event: a runner produced a signal (the Today screen and notifications react to it). */
public record SignalGeneratedEvent(EventMeta meta, UUID signalId, UUID versionId, UUID instrumentId) implements HejjeEvent {
    public UUID id() { return meta.id(); }
    public Instant occurredAt() { return meta.occurredAt(); }
    public CorrelationId correlationId() { return meta.correlationId(); }
}
