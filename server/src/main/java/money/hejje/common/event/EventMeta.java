package money.hejje.common.event;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationContext;
import money.hejje.common.CorrelationId;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;

/** Reusable component that carries the {@link HejjeEvent} identity fields inside an event record. */
public record EventMeta(UUID id, Instant occurredAt, CorrelationId correlationId) implements HejjeEvent {

    public static EventMeta create(HejjeClock clock) {
        return new EventMeta(Ids.newId(), clock.now(), CorrelationContext.current());
    }

    public static EventMeta create(Instant occurredAt, CorrelationId correlationId) {
        return new EventMeta(Ids.newId(), occurredAt, correlationId);
    }
}
