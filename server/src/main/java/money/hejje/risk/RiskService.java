package money.hejje.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.risk.internal.AccountSnapshotBuilder;
import money.hejje.risk.internal.KillSwitchStore;
import money.hejje.risk.internal.RiskLimitsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Public API of the risk module: limits, kill switch, dashboard and position sizing. */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);
    public static final String CLOSE_ALL_CONFIRMATION = "CLOSE ALL";

    private final RiskLimitsStore limitsStore;
    private final KillSwitchStore killSwitchStore;
    private final AccountSnapshotBuilder snapshots;
    private final AuditService audit;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;

    RiskService(RiskLimitsStore limitsStore, KillSwitchStore killSwitchStore, AccountSnapshotBuilder snapshots, AuditService audit,
            HejjeClock clock, ApplicationEventPublisher events) {
        this.limitsStore = limitsStore;
        this.killSwitchStore = killSwitchStore;
        this.snapshots = snapshots;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    public RiskLimits limits(ExecutionMode mode) {
        return limitsStore.find(mode);
    }

    public RiskLimits updateLimits(RiskLimits limits, String actor) {
        limitsStore.update(limits);
        audit.record(AuditEvent.of(AuditEventType.RISK_LIMITS_UPDATED, ActorType.USER).withActorId(actor)
                .withPayload(Map.of("mode", limits.mode().name())));
        return limitsStore.find(limits.mode());
    }

    public KillSwitchState killSwitch(ExecutionMode mode) {
        return killSwitchStore.find(mode);
    }

    /** Applies a kill switch action. CLOSE_ALL_POSITIONS requires the exact confirmation string. */
    public KillSwitchState activate(ExecutionMode mode, KillSwitchAction action, String confirmation, String setBy) {
        if (action == KillSwitchAction.CLOSE_ALL_POSITIONS && !CLOSE_ALL_CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("CLOSE_ALL_POSITIONS requires confirmation \"" + CLOSE_ALL_CONFIRMATION + "\"");
        }
        // every action stops new orders
        killSwitchStore.setStopNewOrders(mode, true, setBy, action.name(), clock.now());
        audit.record(AuditEvent.of(AuditEventType.KILL_SWITCH_ENABLED, ActorType.USER).withActorId(setBy)
                .withPayload(Map.of("mode", mode.name(), "action", action.name())));
        events.publishEvent(new KillSwitchActivated(EventMeta.create(clock), mode, action, "user:" + setBy));
        log.warn("Kill switch {} activated for {} by {}", action, mode, setBy);
        return killSwitchStore.find(mode);
    }

    /** Auto-trips STOP_NEW_ORDERS (e.g. daily loss breach). No-op if already stopping. */
    public void autoTrip(ExecutionMode mode, String reason) {
        if (killSwitchStore.find(mode).stopNewOrders()) {
            return;
        }
        killSwitchStore.setStopNewOrders(mode, true, "system", reason, clock.now());
        audit.record(AuditEvent.of(AuditEventType.KILL_SWITCH_ENABLED, ActorType.SYSTEM).withActorId("risk-engine")
                .withPayload(Map.of("mode", mode.name(), "reason", reason)));
        events.publishEvent(new KillSwitchActivated(EventMeta.create(clock), mode, KillSwitchAction.STOP_NEW_ORDERS, reason));
        log.warn("Kill switch auto-tripped for {}: {}", mode, reason);
    }

    public KillSwitchState rearm(ExecutionMode mode, String actor) {
        killSwitchStore.setStopNewOrders(mode, false, actor, null, clock.now());
        audit.record(AuditEvent.of(AuditEventType.KILL_SWITCH_DISARMED, ActorType.USER).withActorId(actor)
                .withPayload(Map.of("mode", mode.name())));
        return killSwitchStore.find(mode);
    }

    public RiskDashboard dashboard(ExecutionMode mode) {
        AccountSnapshot s = snapshots.build(mode);
        RiskLimits l = limitsStore.find(mode);
        long capital = s.usedMargin().paise() + s.availableCash().paise();
        BigDecimal marginPct = capital <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(s.usedMargin().paise()).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(capital), 2, RoundingMode.HALF_UP);
        return new RiskDashboard(mode, s.realizedPnl(), s.unrealizedPnl(), s.totalPnl(), l.maxLossPerDay(), s.grossExposure(),
                l.maxGrossExposure(), s.openPositionCount(), l.maxOpenPositions(), s.tradesToday(), l.maxTradesPerDay(),
                s.consecutiveLosses(), marginPct, killSwitchStore.find(mode).stopNewOrders());
    }

    public int positionSize(money.hejje.common.Price entry, money.hejje.common.Price stop, Money riskMoney, int lotSize, int maxQty) {
        return PositionSizer.size(entry, stop, riskMoney, lotSize, maxQty);
    }
}
