package money.hejje.execution;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerPosition;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.Product;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.internal.ReconciliationIssueStore;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderEventSource;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.risk.RiskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reconciles Hejje orders, trades and positions against broker truth (PRD section 38). Runs every 30 s during the
 * session, at startup, and on demand. Differences become {@code reconciliation_issue}s; local order state is corrected
 * from the broker; unknown broker orders are imported; a position quantity mismatch is CRITICAL and (when configured)
 * trips the kill switch and blocks execution until resolved.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    static final String POSITION_MISMATCH = "POSITION_MISMATCH";
    static final String EXTERNAL_ORDER = "EXTERNAL_ORDER";

    private final BrokerAdapter broker;
    private final OrderService orders;
    private final ReconciliationIssueStore issues;
    private final RiskService risk;
    private final AuditService audit;
    private final HejjeClock clock;
    private final HejjeProperties properties;
    private final ReconciliationProperties reconciliationProperties;
    private final ApplicationEventPublisher events;

    ReconciliationService(BrokerAdapter broker, OrderService orders, ReconciliationIssueStore issues, RiskService risk,
            AuditService audit, HejjeClock clock, HejjeProperties properties, ReconciliationProperties reconciliationProperties,
            ApplicationEventPublisher events) {
        this.broker = broker;
        this.orders = orders;
        this.issues = issues;
        this.risk = risk;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
        this.reconciliationProperties = reconciliationProperties;
        this.events = events;
    }

    @Scheduled(fixedDelay = 30000)
    void scheduled() {
        if (broker.sessionState() == money.hejje.broker.BrokerSessionState.CONNECTED && clock.isSessionOpen()) {
            try {
                reconcile();
            } catch (RuntimeException e) {
                log.warn("Scheduled reconciliation failed: {}", e.getMessage());
            }
        }
    }

    /** Runs one reconciliation pass. Returns the issues found (open, after this pass). */
    public synchronized List<ReconciliationIssue> reconcile() {
        ExecutionMode mode = properties.mode();
        reconcileOrders(mode);
        reconcilePositions(mode);
        List<ReconciliationIssue> critical = issues.openBySeverity(ReconciliationSeverity.CRITICAL);
        if (!critical.isEmpty() && reconciliationProperties.pauseOnCritical()) {
            risk.autoTrip(mode, "RECONCILIATION");
        }
        return issues.open();
    }

    private void reconcileOrders(ExecutionMode mode) {
        List<BrokerOrder> brokerOrders;
        try {
            brokerOrders = broker.getOrders();
        } catch (BrokerException e) {
            log.warn("Reconciliation: getOrders failed ({})", e.kind());
            return;
        }
        for (BrokerOrder bo : brokerOrders) {
            Optional<HejjeOrder> local = resolveLocal(mode, bo);
            if (local.isPresent()) {
                orders.applyBrokerUpdate(broker.brokerCode(), mode, bo, OrderEventSource.RECONCILIATION);
            } else {
                HejjeOrder imported = orders.importExternal(broker.brokerCode(), mode, bo);
                raise(EXTERNAL_ORDER, ReconciliationSeverity.WARN, bo.instrumentId(), imported.id(), "none",
                        bo.brokerOrderId(), "imported external broker order " + bo.brokerOrderId());
            }
        }
    }

    private Optional<HejjeOrder> resolveLocal(ExecutionMode mode, BrokerOrder bo) {
        if (bo.brokerOrderId() != null) {
            Optional<HejjeOrder> byId = orders.findByBrokerOrderId(broker.brokerCode(), bo.brokerOrderId());
            if (byId.isPresent()) {
                return byId;
            }
        }
        return bo.tag() == null ? Optional.empty() : orders.findByTag(mode, bo.tag());
    }

    private void reconcilePositions(ExecutionMode mode) {
        List<BrokerPosition> brokerPositions;
        try {
            brokerPositions = broker.getPositions();
        } catch (BrokerException e) {
            log.warn("Reconciliation: getPositions failed ({})", e.kind());
            return;
        }
        Map<UUID, Integer> brokerNet = new LinkedHashMap<>();
        for (BrokerPosition p : brokerPositions) {
            if (p.instrumentId() != null) {
                brokerNet.merge(p.instrumentId(), p.netQuantity(), Integer::sum);
            }
        }
        Map<UUID, Integer> localNet = new LinkedHashMap<>();
        for (Position p : orders.positions(mode)) {
            localNet.merge(p.instrumentId(), p.netQuantity(), Integer::sum);
        }
        java.util.Set<UUID> instruments = new java.util.LinkedHashSet<>();
        instruments.addAll(brokerNet.keySet());
        instruments.addAll(localNet.keySet());
        for (UUID instrument : instruments) {
            int broker = brokerNet.getOrDefault(instrument, 0);
            int local = localNet.getOrDefault(instrument, 0);
            if (broker != local) {
                if (!issues.hasOpenLike(POSITION_MISMATCH, instrument, null)) {
                    raise(POSITION_MISMATCH, ReconciliationSeverity.CRITICAL, instrument, null, String.valueOf(local),
                            String.valueOf(broker), "position quantity mismatch: Hejje " + local + " vs broker " + broker);
                }
            } else {
                issues.resolveMatching(POSITION_MISMATCH, instrument, clock.now());
            }
        }
    }

    private void raise(String kind, ReconciliationSeverity severity, UUID instrumentId, UUID orderId, String expected, String observed, String detail) {
        ReconciliationIssue issue = new ReconciliationIssue(Ids.newId(), kind, severity, instrumentId, orderId, expected, observed, detail, clock.now(), null);
        issues.insert(issue);
        log.warn("Reconciliation {} [{}]: {}", kind, severity, detail);
        audit.record(AuditEvent.of(AuditEventType.RECONCILIATION_ISSUE_DETECTED, ActorType.SYSTEM)
                .withPayload(Map.of("kind", kind, "severity", severity.name(), "detail", detail)));
        events.publishEvent(new ReconciliationIssueEvent(EventMeta.create(clock), issue.id(), kind, severity));
    }

    public List<ReconciliationIssue> openIssues() {
        return issues.open();
    }

    public boolean hasCriticalOpen() {
        return !issues.openBySeverity(ReconciliationSeverity.CRITICAL).isEmpty();
    }

    public ReconciliationIssue resolve(UUID id) {
        ReconciliationIssue issue = issues.findById(id).orElseThrow(() -> new IllegalArgumentException("No issue " + id));
        issues.resolve(id, clock.now());
        audit.record(AuditEvent.of(AuditEventType.RECONCILIATION_ISSUE_RESOLVED, ActorType.USER).withPayload(Map.of("id", id.toString())));
        return issues.findById(id).orElse(issue);
    }
}
