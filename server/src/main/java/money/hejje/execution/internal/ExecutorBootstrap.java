package money.hejje.execution.internal;

import java.util.Map;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerSessionState;
import money.hejje.common.ActorType;
import money.hejje.common.config.HejjeProperties;
import money.hejje.execution.ReconciliationService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderEventSource;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.system.ReadinessCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Startup recovery (PRD section 63): acquire the executor lease, then (once the broker session is up) fetch and reconcile
 * broker state, restore in-flight orders, and enable execution. Reported as the {@code bootstrap} readiness check.
 */
@Component
class ExecutorBootstrap implements ReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(ExecutorBootstrap.class);

    private final ExecutorLease lease;
    private final BrokerAdapter broker;
    private final OrderService orders;
    private final ReconciliationService reconciliation;
    private final UnknownOrderResolver unknownResolver;
    private final AuditService audit;
    private final HejjeProperties properties;
    private volatile boolean complete;
    private volatile String detail = "not started";

    ExecutorBootstrap(ExecutorLease lease, BrokerAdapter broker, OrderService orders, @Lazy ReconciliationService reconciliation,
            UnknownOrderResolver unknownResolver, AuditService audit, HejjeProperties properties) {
        this.lease = lease;
        this.broker = broker;
        this.orders = orders;
        this.reconciliation = reconciliation;
        this.unknownResolver = unknownResolver;
        this.audit = audit;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void run() {
        boolean acquired = lease.acquire();
        if (lease.required() && !acquired) {
            detail = "executor lease held by another process; running read-only";
            log.warn(detail);
            return;
        }
        audit.record(AuditEvent.of(AuditEventType.EXECUTOR_LEASE_ACQUIRED, ActorType.SYSTEM).withActorId(lease.owner()));
        if (broker.sessionState() == BrokerSessionState.CONNECTED) {
            try {
                reconciliation.reconcile();
                restoreInFlight();
            } catch (RuntimeException e) {
                log.warn("Bootstrap reconciliation failed: {}", e.getMessage());
            }
        } else {
            log.info("Bootstrap: broker not connected; skipping reconciliation until login");
        }
        complete = true;
        detail = "execution enabled";
        audit.record(AuditEvent.of(AuditEventType.EXECUTION_ENABLED, ActorType.SYSTEM).withActorId(lease.owner()));
        log.info("Executor bootstrap complete; execution enabled");
    }

    private void restoreInFlight() {
        for (HejjeOrder order : orders.live(properties.mode())) {
            if (order.state() == OrderState.UNKNOWN && order.brokerOrderId() == null) {
                unknownResolver.resolveNow(order.id());
            } else if ((order.state() == OrderState.UNKNOWN || order.state() == OrderState.MODIFY_PENDING
                    || order.state() == OrderState.CANCEL_PENDING || order.state() == OrderState.SUBMITTING) && order.brokerOrderId() != null) {
                try {
                    BrokerOrder brokerOrder = broker.getOrder(new BrokerOrderRef(order.brokerOrderId()));
                    orders.applyBrokerUpdate(broker.brokerCode(), properties.mode(), brokerOrder, OrderEventSource.RECONCILIATION);
                } catch (BrokerException e) {
                    log.debug("Restore of order {} failed ({})", order.id(), e.kind());
                }
            }
        }
    }

    /** Test hook: run bootstrap synchronously. */
    public void runNow() {
        run();
    }

    @Override
    public String name() {
        return "bootstrap";
    }

    @Override
    public CheckResult result() {
        return complete ? CheckResult.ok(detail) : CheckResult.blocking(detail);
    }
}
