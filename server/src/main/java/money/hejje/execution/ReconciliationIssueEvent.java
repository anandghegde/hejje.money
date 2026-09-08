package money.hejje.execution;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

public record ReconciliationIssueEvent(EventMeta meta, UUID issueId, String kind, ReconciliationSeverity severity) implements HejjeEvent {
    public UUID id() { return meta.id(); }
    public Instant occurredAt() { return meta.occurredAt(); }
    public CorrelationId correlationId() { return meta.correlationId(); }
}
