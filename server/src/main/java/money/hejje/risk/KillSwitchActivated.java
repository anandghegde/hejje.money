package money.hejje.risk;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.ExecutionMode;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

/** Published when a kill switch action is taken. The execution module performs CANCEL_ALL_OPEN / CLOSE_ALL_POSITIONS. */
public record KillSwitchActivated(EventMeta meta, ExecutionMode mode, KillSwitchAction action, String reason) implements HejjeEvent {
    public UUID id() { return meta.id(); }
    public Instant occurredAt() { return meta.occurredAt(); }
    public CorrelationId correlationId() { return meta.correlationId(); }
}
