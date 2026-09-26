package money.hejje.signals;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.ExecutionMode;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

/**
 * Durable event: the engine recorded the close of a strategy-managed position, with its reason. Published after the
 * {@code strategy_position} row is CLOSED, so a listener (the post-trade review) sees the close reason; the orders
 * module's {@code PositionChangedEvent} for the same fill can arrive before the engine has processed that fill.
 */
public record StrategyPositionClosedEvent(EventMeta meta, UUID strategyPositionId, UUID entryOrderId, UUID instrumentId, ExecutionMode mode,
        UUID strategyId, CloseReason reason) implements HejjeEvent {
    public UUID id() { return meta.id(); }
    public Instant occurredAt() { return meta.occurredAt(); }
    public CorrelationId correlationId() { return meta.correlationId(); }
}
