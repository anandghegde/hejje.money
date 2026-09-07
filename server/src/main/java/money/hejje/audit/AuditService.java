package money.hejje.audit;

import money.hejje.audit.internal.AuditStore;
import money.hejje.common.CorrelationContext;
import money.hejje.common.CorrelationId;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import org.springframework.stereotype.Service;

/** Public API of the audit module. Every state change in the system is recorded through {@link #record}. */
@Service
public class AuditService {

    private final AuditStore store;
    private final HejjeClock clock;

    AuditService(AuditStore store, HejjeClock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Appends an event. The correlation id defaults to the one bound to the current thread. */
    public AuditRecord record(AuditEvent event) {
        CorrelationId correlationId = event.correlationId() != null ? event.correlationId() : CorrelationContext.current();
        AuditRecord record = new AuditRecord(Ids.newId(), clock.now(), event.type(), event.actorType(), event.actorId(),
                correlationId, event.strategyId(), event.signalId(), event.orderIntentId(), event.orderId(),
                event.brokerRef(), event.clientSource(), event.payload());
        store.insert(record);
        return record;
    }

    public AuditPage query(AuditQuery query) {
        return store.find(query);
    }
}
