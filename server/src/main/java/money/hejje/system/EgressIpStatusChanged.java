package money.hejje.system;

import java.util.List;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

/** Published whenever the egress IP verification result changes. */
public record EgressIpStatusChanged(EventMeta meta, EgressIpStatus previous, EgressIpStatus current,
        List<String> observedIps, List<String> expectedIps) implements HejjeEvent {

    @Override
    public java.util.UUID id() {
        return meta.id();
    }

    @Override
    public java.time.Instant occurredAt() {
        return meta.occurredAt();
    }

    @Override
    public money.hejje.common.CorrelationId correlationId() {
        return meta.correlationId();
    }
}
