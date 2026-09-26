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

    private final money.hejje.execution.internal.ExecutorLease lease;

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    static final String POSITION_MISMATCH = "POSITION_MISMATCH";
    static final String EXTERNAL_ORDER = "EXTERNAL_ORDER";
    public static final String HOLDINGS_MISMATCH = "HOLDINGS_MISMATCH";
    /** A swing position closed this recently is still compared with the holdings (the sale settles the next session). */
    static final java.time.Duration HOLDINGS_LOOKBACK = java.time.Duration.ofDays(7);

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
            ApplicationEventPublisher events, @org.springframework.context.annotation.Lazy money.hejje.execution.internal.ExecutorLease lease) {
        this.lease = lease;
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
        if (lease.isActive() && broker.sessionState() == money.hejje.broker.BrokerSessionState.CONNECTED && clock.isSessionOpen()) {
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
        // delivery (CNC) quantities move to the broker's holdings the next session: the swing book reconciles against
        // holdings plus today's delivery positions in reconcileHoldings (plan M11.1)
        Map<UUID, Integer> brokerNet = new LinkedHashMap<>();
        for (BrokerPosition p : brokerPositions) {
            if (p.instrumentId() != null && p.product() != Product.CNC) {
                brokerNet.merge(p.instrumentId(), p.netQuantity(), Integer::sum);
            }
        }
        Map<UUID, Integer> localNet = new LinkedHashMap<>();
        for (Position p : orders.positions(mode)) {
            if (p.product() != Product.CNC) {
                localNet.merge(p.instrumentId(), p.netQuantity(), Integer::sum);
            }
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

    /**
     * The swing book against the broker's delivery side (plan M11.1): per scrip, Hejje's delivery (CNC) quantity must equal
     * the broker's holdings (settled plus T1, i.e. earlier sessions' buys) plus today's delivery position. Only scrips the
     * swing book holds, closed within {@link #HOLDINGS_LOOKBACK}, or traded for delivery today are compared, so the rest of
     * a real account's holdings are left alone. A difference is a WARN {@code HOLDINGS_MISMATCH}; a match resolves it.
     * Returns the open holdings issues.
     */
    public synchronized List<ReconciliationIssue> reconcileHoldings() {
        ExecutionMode mode = properties.mode();
        List<BrokerPosition> brokerPositions;
        List<money.hejje.broker.BrokerHolding> holdings;
        try {
            brokerPositions = broker.getPositions();
            holdings = broker.getHoldings();
        } catch (BrokerException e) {
            log.warn("Holdings reconciliation: broker read failed ({})", e.kind());
            return issues.open().stream().filter(i -> HOLDINGS_MISMATCH.equals(i.kind())).toList();
        }
        Instant since = clock.now().minus(HOLDINGS_LOOKBACK);
        Map<UUID, Integer> local = new LinkedHashMap<>();
        for (Position p : orders.positions(mode)) {
            if (p.product() == Product.CNC && (!p.isFlat() || p.updatedAt().isAfter(since))) {
                local.merge(p.instrumentId(), p.netQuantity(), Integer::sum);
            }
        }
        Map<UUID, Integer> brokerQty = new LinkedHashMap<>();
        java.util.Set<UUID> tradedToday = new java.util.LinkedHashSet<>();
        for (BrokerPosition p : brokerPositions) {
            if (p.instrumentId() != null && p.product() == Product.CNC) {
                brokerQty.merge(p.instrumentId(), p.netQuantity(), Integer::sum);
                tradedToday.add(p.instrumentId());
            }
        }
        for (money.hejje.broker.BrokerHolding h : holdings) {
            if (h.instrumentId() != null) {
                brokerQty.merge(h.instrumentId(), h.totalQuantity(), Integer::sum);
            }
        }
        java.util.Set<UUID> scrips = new java.util.LinkedHashSet<>(local.keySet());
        scrips.addAll(tradedToday);
        for (UUID instrument : scrips) {
            int hejje = local.getOrDefault(instrument, 0);
            int atBroker = brokerQty.getOrDefault(instrument, 0);
            if (hejje != atBroker) {
                if (!issues.hasOpenLike(HOLDINGS_MISMATCH, instrument, null)) {
                    raise(HOLDINGS_MISMATCH, ReconciliationSeverity.WARN, instrument, null, String.valueOf(hejje), String.valueOf(atBroker),
                            "delivery quantity mismatch: swing book " + hejje + " vs broker holdings and delivery positions " + atBroker);
                }
            } else {
                issues.resolveMatching(HOLDINGS_MISMATCH, instrument, clock.now());
            }
        }
        return issues.open().stream().filter(i -> HOLDINGS_MISMATCH.equals(i.kind())).toList();
    }

    /** Raises an issue unless one of that kind is already open for the instrument (the GTT reconciliation, plan M11.2). */
    boolean raiseOnce(String kind, ReconciliationSeverity severity, UUID instrumentId, String expected, String observed, String detail) {
        if (issues.hasOpenLike(kind, instrumentId, null)) {
            return false;
        }
        raise(kind, severity, instrumentId, null, expected, observed, detail);
        return true;
    }

    void resolveKind(String kind, UUID instrumentId) {
        issues.resolveMatching(kind, instrumentId, clock.now());
    }

    List<ReconciliationIssue> openOfKinds(java.util.Set<String> kinds) {
        return issues.open().stream().filter(i -> kinds.contains(i.kind())).toList();
    }

    private void raise(String kind, ReconciliationSeverity severity, UUID instrumentId, UUID orderId, String expected, String observed, String detail) {
        ReconciliationIssue issue = new ReconciliationIssue(Ids.newId(), kind, severity, instrumentId, orderId, expected, observed, detail, clock.now(), null,
                broker.brokerCode());
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
