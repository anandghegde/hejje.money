package money.hejje.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

public record OrderFilledEvent(EventMeta meta, UUID orderId, UUID instrumentId, int filledQuantity, BigDecimal averagePrice, boolean complete) implements HejjeEvent {
    public UUID id() { return meta.id(); }
    public Instant occurredAt() { return meta.occurredAt(); }
    public CorrelationId correlationId() { return meta.correlationId(); }
}
