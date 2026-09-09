package money.hejje.backtest;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.HejjeEvent;

/** Durable event: a backtest reached DONE. The scoring module recomputes the version's score on it. */
public record BacktestFinished(EventMeta meta, UUID backtestId, UUID versionId) implements HejjeEvent {
    public UUID id() { return meta.id(); }
    public Instant occurredAt() { return meta.occurredAt(); }
    public CorrelationId correlationId() { return meta.correlationId(); }
}
