package money.hejje.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.Gtt;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.internal.GttStore;
import money.hejje.market.MarketService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The broker-side stops of the swing book (plan M11.2). Every open delivery position is protected by a GTT at the broker
 * (an OCO of stop and goal, or a single stop), which fires even while Hejje is down. Hejje places it after the entry
 * fills, sizes it to the position, trails it tighten-only, cancels it only together with the position's close (or its
 * replacement), and reconciles it with the broker's list. A position without a confirmed GTT is an incident: audited
 * ({@code GTT_MISSING}), raised as a reconciliation issue (notified) and reported by {@link #unprotected} so that new
 * swing entries are refused until it is fixed. Orphans at the broker are flagged, never deleted.
 */
@Service
public class GttService {

    private static final Logger log = LoggerFactory.getLogger(GttService.class);
    public static final String GTT_MISSING = "GTT_MISSING";
    public static final String GTT_MISMATCH = "GTT_MISMATCH";
    public static final String GTT_ORPHAN = "GTT_ORPHAN";
    static final Set<String> KINDS = Set.of(GTT_MISSING, GTT_MISMATCH, GTT_ORPHAN);

    private final BrokerAdapter broker;
    private final OrderService orders;
    private final GttStore store;
    private final MarketService market;
    private final AuditService audit;
    private final ReconciliationService reconciliation;
    private final HejjeClock clock;
    private final HejjeProperties properties;
    private final org.springframework.context.ApplicationEventPublisher events;

    GttService(BrokerAdapter broker, OrderService orders, GttStore store, MarketService market, AuditService audit,
            ReconciliationService reconciliation, HejjeClock clock, HejjeProperties properties, org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.broker = broker;
        this.orders = orders;
        this.store = store;
        this.market = market;
        this.audit = audit;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.properties = properties;
    }

    // --- reads -----------------------------------------------------------------------------------------------------------

    /** The position's GTT in force (ACTIVE), if any. */
    public Optional<PositionGtt> active(UUID positionId) {
        return store.live(positionId).filter(g -> g.status() == PositionGtt.Status.ACTIVE);
    }

    /** The position's ACTIVE or MISSING GTT. */
    public Optional<PositionGtt> live(UUID positionId) {
        return store.live(positionId);
    }

    public List<PositionGtt> live(ExecutionMode mode) {
        return store.live(mode);
    }

    /** Every GTT a position has had, oldest first (placed, replaced, triggered, cancelled). */
    public List<PositionGtt> history(UUID positionId) {
        return store.history(positionId);
    }

    /** Open (long) delivery positions without an ACTIVE GTT for their whole quantity: new swing entries wait until these are fixed. */
    public List<Position> unprotected(ExecutionMode mode) {
        List<Position> out = new ArrayList<>();
        for (Position p : openDelivery(mode)) {
            Optional<PositionGtt> g = active(p.id());
            if (g.isEmpty() || g.get().quantity() != p.netQuantity()) {
                out.add(p);
            }
        }
        return out;
    }

    // --- placing, sizing, trailing, cancelling ---------------------------------------------------------------------------

    /**
     * Reacts to a fill of a Hejje delivery order (PAPER and SIM only, plan Phase 11 gate): an entry fill places the OCO GTT
     * from the entry's stop and goal, or sizes the existing one to the filled quantity; a sell resizes it, and a sell that
     * flattens the position cancels it. An entry without a stop, or a placement the broker refuses, is a GTT_MISSING incident.
     */
    public synchronized void onDeliveryFill(UUID orderId) {
        if (properties.mode() != ExecutionMode.PAPER && properties.mode() != ExecutionMode.SIM) {
            return; // LIVE swing needs the recorded M11.7 decision; nothing is placed at a real broker before it
        }
        HejjeOrder order = orders.findById(orderId).orElse(null);
        if (order == null || order.product() != Product.CNC || order.intentId() == null) {
            return;
        }
        OrderIntent intent = orders.findIntent(order.intentId()).orElse(null);
        UUID strategyId = intent == null ? null : intent.strategyId();
        Position p = orders.positions(order.mode()).stream().filter(x -> x.instrumentId().equals(order.instrumentId()) && x.product() == Product.CNC
                && Objects.equals(x.strategyId(), strategyId)).findFirst().orElse(null);
        if (p == null) {
            return;
        }
        Optional<PositionGtt> current = active(p.id());
        if (p.netQuantity() <= 0) {
            if (current.isPresent()) {
                cancel(p.id(), "position closed", "system");
            }
            return;
        }
        if (current.isPresent()) {
            resize(p);
            return;
        }
        if (order.side() != Side.BUY) {
            return;
        }
        if (intent.stopPrice() == null) {
            missing(p, "the entry carried no stop to protect the position with");
            return;
        }
        try {
            protect(p, intent.stopPrice().value(), intent.targetPrice() == null ? null : intent.targetPrice().value(), "entry");
        } catch (BrokerException e) {
            missing(p, "placing the GTT failed: " + e.brokerMessage());
        }
    }

    /** Places the position's GTT (OCO when a goal above the stop is given, else a single stop) for its whole quantity. */
    public synchronized PositionGtt protect(Position p, BigDecimal stop, BigDecimal goal, String actor) {
        if (p.product() != Product.CNC || p.netQuantity() <= 0) {
            throw new IllegalArgumentException("only a long delivery position is protected by a GTT");
        }
        Optional<PositionGtt> existing = store.live(p.id());
        if (existing.isPresent() && existing.get().status() == PositionGtt.Status.ACTIVE) {
            return resize(p);
        }
        String gttId = broker.placeGtt(request(p.instrumentId(), p.netQuantity(), stop, goal, p.averagePrice()));
        Instant now = clock.now();
        // a MISSING predecessor is replaced by the new protection in the same operation
        existing.ifPresent(old -> store.update(old.id(), old.brokerGttId(), old.quantity(), old.stop(), PositionGtt.Status.CANCELLED, null, now));
        PositionGtt g = new PositionGtt(Ids.newId(), p.mode(), broker.brokerCode(), gttId, p.id(), p.instrumentId(), p.netQuantity(), stop, goal,
                PositionGtt.Status.ACTIVE, null, now, now, null);
        store.insert(g);
        reconciliation.resolveKind(GTT_MISSING, p.instrumentId());
        audit.record(AuditEvent.of(AuditEventType.GTT_PLACED, ActorType.SYSTEM).withActorId(actor).withBrokerRef(gttId)
                .withPayload(payload(g, Map.of("replaced", existing.isPresent()))));
        log.info("GTT {} placed for delivery position {}: {} @ stop {} goal {}", gttId, p.id(), p.netQuantity(), stop, goal);
        Map<String, Object> note = new LinkedHashMap<>(payload(g, Map.of()));
        note.put("event", "GTT_PLACED");
        events.publishEvent(new money.hejje.common.ClientNotification("swing", note)); // plan M11.6
        return g;
    }

    /** Sizes the position's GTT to its current quantity (a partial fill, an add, a partial exit). */
    public synchronized PositionGtt resize(Position p) {
        PositionGtt g = active(p.id()).orElseThrow(() -> new IllegalStateException("position " + p.id() + " has no active GTT"));
        if (g.quantity() == p.netQuantity()) {
            return g;
        }
        broker.modifyGtt(g.brokerGttId(), request(p.instrumentId(), p.netQuantity(), g.stop(), g.goal(), p.averagePrice()));
        store.update(g.id(), g.brokerGttId(), p.netQuantity(), g.stop(), g.status(), null, clock.now());
        audit.record(AuditEvent.of(AuditEventType.GTT_MODIFIED, ActorType.SYSTEM).withBrokerRef(g.brokerGttId())
                .withPayload(payload(g, Map.of("quantityFrom", g.quantity(), "quantityTo", p.netQuantity()))));
        return store.live(p.id()).orElseThrow();
    }

    /**
     * Moves the position's stop. Stops only tighten (move up for a long); a lower stop is refused unless {@code manualWiden}
     * is set by a user action, which is audited as a widening.
     */
    public synchronized PositionGtt moveStop(UUID positionId, BigDecimal stop, boolean manualWiden, String actor) {
        PositionGtt g = active(positionId).orElseThrow(() -> new IllegalStateException("position " + positionId + " has no active GTT"));
        int cmp = stop.compareTo(g.stop());
        if (cmp == 0) {
            return g;
        }
        if (cmp < 0 && !manualWiden) {
            throw new IllegalArgumentException("stops only tighten: " + stop.toPlainString() + " is below the stop " + g.stop().toPlainString());
        }
        if (g.goal() != null && stop.compareTo(g.goal()) >= 0) {
            throw new IllegalArgumentException("the stop must stay below the goal " + g.goal().toPlainString());
        }
        Position p = orders.findPosition(positionId).orElseThrow();
        broker.modifyGtt(g.brokerGttId(), request(g.instrumentId(), g.quantity(), stop, g.goal(), p.averagePrice()));
        store.update(g.id(), g.brokerGttId(), g.quantity(), stop, g.status(), null, clock.now());
        audit.record(AuditEvent.of(AuditEventType.GTT_MODIFIED, cmp < 0 ? ActorType.USER : ActorType.SYSTEM)
                .withActorId(actor).withBrokerRef(g.brokerGttId())
                .withPayload(payload(g, Map.of("stopFrom", g.stop().toPlainString(), "stopTo", stop.toPlainString(), "widened", cmp < 0))));
        return store.live(positionId).orElseThrow();
    }

    /** Cancels the position's GTT at the broker (as part of closing the position). A GTT already gone at the broker is recorded as cancelled. */
    public synchronized void cancel(UUID positionId, String reason, String actor) {
        Optional<PositionGtt> current = active(positionId);
        if (current.isEmpty()) {
            return;
        }
        PositionGtt g = current.get();
        try {
            broker.cancelGtt(g.brokerGttId());
        } catch (BrokerException e) {
            if (e.kind() != BrokerException.Kind.REJECTED && e.kind() != BrokerException.Kind.INPUT) {
                throw e; // outcome unknown: the GTT may still be live; the position keeps it
            }
            log.info("GTT {} was already gone at the broker ({})", g.brokerGttId(), e.brokerMessage());
        }
        store.update(g.id(), g.brokerGttId(), g.quantity(), g.stop(), PositionGtt.Status.CANCELLED, null, clock.now());
        audit.record(AuditEvent.of(AuditEventType.GTT_CANCELLED, ActorType.SYSTEM).withActorId(actor).withBrokerRef(g.brokerGttId())
                .withPayload(payload(g, Map.of("reason", reason))));
    }

    /**
     * An order the broker reports that Hejje did not place: when it is the exit a GTT of this instrument placed (a delivery
     * sell and the broker lists that GTT as triggered), the order is taken over and the GTT marked TRIGGERED. Returns
     * whether it was.
     */
    public synchronized boolean onUnknownOrder(BrokerOrder bo) {
        if (bo.product() != Product.CNC || bo.side() != Side.SELL || bo.instrumentId() == null) {
            return false;
        }
        ExecutionMode mode = properties.mode();
        List<PositionGtt> candidates = store.activeByInstrument(mode, bo.instrumentId());
        if (candidates.isEmpty() || orders.findByBrokerOrderId(broker.brokerCode(), bo.brokerOrderId()).isPresent()) {
            return false;
        }
        Map<String, Gtt.Snapshot> atBroker = snapshots();
        for (PositionGtt g : candidates) {
            Gtt.Snapshot s = atBroker.get(g.brokerGttId());
            if (s != null && s.status() == Gtt.Status.TRIGGERED && (s.triggeredOrderId() == null || s.triggeredOrderId().equals(bo.brokerOrderId()))) {
                HejjeOrder imported = orders.importExternal(broker.brokerCode(), mode, bo);
                triggered(g, bo.brokerOrderId(), imported.id());
                return true;
            }
        }
        return false;
    }

    private void triggered(PositionGtt g, String brokerOrderId, UUID orderId) {
        store.update(g.id(), g.brokerGttId(), g.quantity(), g.stop(), PositionGtt.Status.TRIGGERED, brokerOrderId, clock.now());
        AuditEvent event = AuditEvent.of(AuditEventType.GTT_TRIGGERED, ActorType.SYSTEM).withBrokerRef(g.brokerGttId())
                .withPayload(payload(g, Map.of("brokerOrderId", String.valueOf(brokerOrderId))));
        audit.record(orderId == null ? event : event.withOrderId(orderId));
        log.info("GTT {} of position {} triggered (order {})", g.brokerGttId(), g.positionId(), brokerOrderId);
    }

    // --- reconciliation ----------------------------------------------------------------------------------------------------

    /**
     * Matches the broker's GTTs to the open delivery positions (at startup, before the open, after the close): a position
     * without one, or whose GTT the broker no longer lists as active, is {@code GTT_MISSING}; a wrong quantity or stop is
     * {@code GTT_MISMATCH}; an active GTT at the broker that protects no open position is {@code GTT_ORPHAN} and is left in
     * place. Every issue is a WARN reconciliation issue (notified); none trips the intraday kill switch. Only GTTs Hejje
     * placed count as orphans at a real broker (the account's own GTTs are not Hejje's business); a simulated broker's are
     * all Hejje's. Returns the open GTT issues.
     */
    public synchronized List<ReconciliationIssue> reconcile() {
        ExecutionMode mode = properties.mode();
        if (broker.sessionState() != BrokerSessionState.CONNECTED) {
            return reconciliation.openOfKinds(KINDS);
        }
        List<Gtt.Snapshot> listed;
        try {
            listed = broker.getGtts();
        } catch (BrokerException e) {
            log.warn("GTT reconciliation: listing failed ({})", e.kind());
            return reconciliation.openOfKinds(KINDS);
        }
        Map<String, Gtt.Snapshot> atBroker = new LinkedHashMap<>();
        listed.forEach(s -> atBroker.put(s.id(), s));
        Set<String> protecting = new HashSet<>();
        Instant now = clock.now();
        for (Position p : openDelivery(mode)) {
            PositionGtt g = store.live(p.id()).orElse(null);
            if (g == null) {
                missing(p, "no GTT protects the position");
                continue;
            }
            Gtt.Snapshot s = atBroker.get(g.brokerGttId());
            if (s == null || !s.status().isLive()) {
                if (s != null && s.status() == Gtt.Status.TRIGGERED) {
                    triggered(g, s.triggeredOrderId(), null); // its exit order is taken over by the order reconciliation
                    continue;
                }
                if (g.status() == PositionGtt.Status.ACTIVE) {
                    store.update(g.id(), g.brokerGttId(), g.quantity(), g.stop(), PositionGtt.Status.MISSING, null, now);
                }
                missing(p, "the broker " + (s == null ? "no longer lists GTT " + g.brokerGttId() : "reports GTT " + g.brokerGttId() + " " + s.status()));
                continue;
            }
            protecting.add(s.id());
            if (g.status() == PositionGtt.Status.MISSING) {
                store.update(g.id(), g.brokerGttId(), g.quantity(), g.stop(), PositionGtt.Status.ACTIVE, null, now);
            }
            store.confirm(g.id(), now);
            reconciliation.resolveKind(GTT_MISSING, p.instrumentId());
            BigDecimal stopAtBroker = s.triggers().isEmpty() ? null : s.triggers().get(0);
            if (s.quantity() != p.netQuantity() || stopAtBroker == null || stopAtBroker.compareTo(g.stop()) != 0) {
                reconciliation.raiseOnce(GTT_MISMATCH, ReconciliationSeverity.WARN, p.instrumentId(), p.netQuantity() + " @ " + g.stop().toPlainString(),
                        s.quantity() + " @ " + (stopAtBroker == null ? "?" : stopAtBroker.toPlainString()),
                        "GTT " + s.id() + " does not match the position: quantity or stop differ");
            } else {
                reconciliation.resolveKind(GTT_MISMATCH, p.instrumentId());
            }
        }
        boolean simulated = mode == ExecutionMode.PAPER || mode == ExecutionMode.SIM;
        for (Gtt.Snapshot s : listed) {
            if (s.status().isLive() && !protecting.contains(s.id()) && (simulated || store.known(broker.brokerCode(), s.id()))) {
                reconciliation.raiseOnce(GTT_ORPHAN, ReconciliationSeverity.WARN, s.instrumentId(), "none", s.id(),
                        "GTT " + s.id() + " at the broker protects no open delivery position; it is left in place (never deleted automatically)");
            }
        }
        return reconciliation.openOfKinds(KINDS);
    }

    /** An open delivery position without protection: audited once per incident and raised as an issue. */
    private void missing(Position p, String why) {
        boolean raised = reconciliation.raiseOnce(GTT_MISSING, ReconciliationSeverity.WARN, p.instrumentId(), "GTT for " + p.netQuantity(), "none",
                "delivery position " + p.id() + " is unprotected: " + why + "; new swing entries are blocked until it is fixed");
        if (raised) {
            audit.record(AuditEvent.of(AuditEventType.GTT_MISSING, ActorType.SYSTEM)
                    .withPayload(Map.of("positionId", p.id().toString(), "instrumentId", p.instrumentId().toString(), "quantity", p.netQuantity(), "reason", why)));
            log.warn("Delivery position {} is unprotected: {}", p.id(), why);
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------------------------

    private List<Position> openDelivery(ExecutionMode mode) {
        return orders.openPositions(mode).stream().filter(p -> p.product() == Product.CNC && p.netQuantity() > 0).toList();
    }

    private Map<String, Gtt.Snapshot> snapshots() {
        Map<String, Gtt.Snapshot> out = new LinkedHashMap<>();
        try {
            broker.getGtts().forEach(s -> out.put(s.id(), s));
        } catch (BrokerException e) {
            log.warn("Listing GTTs failed ({})", e.kind());
        }
        return out;
    }

    /** The broker request: sell the quantity at MARKET when the stop is touched, and (OCO) at the goal by LIMIT when it is reached. */
    private Gtt.Request request(UUID instrumentId, int quantity, BigDecimal stop, BigDecimal goal, BigDecimal fallbackPrice) {
        BigDecimal last = market.lastPrice(instrumentId).orElse(fallbackPrice);
        Gtt.Leg stopLeg = new Gtt.Leg(Side.SELL, quantity, OrderType.MARKET, stop, Product.CNC);
        if (goal != null && goal.compareTo(stop) > 0) {
            return new Gtt.Request(instrumentId, Gtt.Type.OCO, List.of(stop, goal), last, List.of(stopLeg, new Gtt.Leg(Side.SELL, quantity, OrderType.LIMIT, goal, Product.CNC)));
        }
        return new Gtt.Request(instrumentId, Gtt.Type.SINGLE, List.of(stop), last, List.of(stopLeg));
    }

    private static Map<String, Object> payload(PositionGtt g, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("positionId", g.positionId().toString());
        m.put("instrumentId", g.instrumentId().toString());
        m.put("gttId", g.brokerGttId());
        m.put("quantity", g.quantity());
        m.put("stop", g.stop().toPlainString());
        m.put("goal", g.goal() == null ? "none" : g.goal().toPlainString());
        m.putAll(extra);
        return m;
    }
}
