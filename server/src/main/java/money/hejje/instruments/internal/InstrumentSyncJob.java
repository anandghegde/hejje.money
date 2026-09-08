package money.hejje.instruments.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerInstrument;
import money.hejje.common.ActorType;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.InstrumentProperties;
import money.hejje.instruments.InstrumentSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pulls the broker instrument master, upserts instruments and mappings, deactivates instruments that disappeared and
 * audits the counts. Scheduled daily at 08:00 IST on trading days, on demand through the API, and optionally at startup.
 */
@Component
public class InstrumentSyncJob {

    private static final Logger log = LoggerFactory.getLogger(InstrumentSyncJob.class);

    private final BrokerAdapter broker;
    private final InstrumentStore store;
    private final AuditService audit;
    private final HejjeClock clock;
    private final InstrumentProperties properties;

    InstrumentSyncJob(BrokerAdapter broker, InstrumentStore store, AuditService audit, HejjeClock clock, InstrumentProperties properties) {
        this.broker = broker;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(cron = "${hejje.instruments.sync-cron:0 0 8 * * MON-FRI}", zone = "Asia/Kolkata")
    void scheduled() {
        if (!clock.isTradingDay(clock.today())) {
            log.info("Instrument sync skipped: {} is not a trading day", clock.today());
            return;
        }
        run();
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (properties.syncOnStartup()) {
            try {
                run();
            } catch (RuntimeException e) {
                log.warn("Startup instrument sync failed: {}", e.getMessage());
            }
        }
    }

    @Transactional
    public synchronized InstrumentSyncResult run() {
        Instant start = clock.now();
        List<BrokerInstrument> rows = broker.getInstruments();
        int upserted = store.upsertAll(broker.brokerCode(), rows, start);
        int deactivated = store.deactivateMissing(broker.brokerCode(), start, start);
        long active = store.countActive();
        InstrumentSyncResult result = new InstrumentSyncResult(broker.brokerCode(), rows.size(), upserted, deactivated, active, start);
        log.info("Instrument sync from {}: received={} upserted={} deactivated={} active={}", result.broker(), result.received(),
                result.upserted(), result.deactivated(), result.activeAfter());
        audit.record(AuditEvent.of(AuditEventType.INSTRUMENTS_SYNCED, ActorType.SYSTEM).withActorId("instrument-sync")
                .withPayload(Map.of("broker", result.broker(), "received", result.received(), "upserted", result.upserted(),
                        "deactivated", result.deactivated(), "activeAfter", result.activeAfter())));
        return result;
    }
}
