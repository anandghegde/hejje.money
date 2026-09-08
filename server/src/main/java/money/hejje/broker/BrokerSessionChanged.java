package money.hejje.broker;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

/** Published whenever the broker session state changes. */
public record BrokerSessionChanged(EventMeta meta, String broker, BrokerSessionState previous, BrokerSessionState current, String detail)
        implements HejjeEvent {

    @Override
    public UUID id() {
        return meta.id();
    }

    @Override
    public Instant occurredAt() {
        return meta.occurredAt();
    }

    @Override
    public CorrelationId correlationId() {
        return meta.correlationId();
    }
}
