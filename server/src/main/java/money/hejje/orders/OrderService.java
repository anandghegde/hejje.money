package money.hejje.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.Side;
import money.hejje.common.costs.CostBreakdown;
import money.hejje.common.costs.CostFill;
import money.hejje.common.costs.CostModel;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.orders.internal.OrderIntentStore;
import money.hejje.orders.internal.OrderStore;
import money.hejje.orders.internal.PositionService;
import money.hejje.orders.internal.PositionStore;
import money.hejje.orders.internal.TradeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of the orders module: create and transition orders through the state machine, apply broker order updates
 * (fills to trades and positions), and read orders, events, trades and positions. The execution module drives this.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final java.util.concurrent.ConcurrentHashMap<UUID, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

    private final OrderStore orders;
    private final OrderIntentStore intents;
    private final TradeStore trades;
    private final PositionStore positions;
    private final PositionService positionService;
    private final InstrumentService instruments;
    private final CostModel costModel;
    private final AuditService audit;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;

    OrderService(OrderStore orders, OrderIntentStore intents, TradeStore trades, PositionStore positions, PositionService positionService,
            InstrumentService instruments, CostModel costModel, AuditService audit, HejjeClock clock, ApplicationEventPublisher events) {
        this.orders = orders;
        this.intents = intents;
        this.trades = trades;
        this.positions = positions;
        this.positionService = positionService;
        this.instruments = instruments;
        this.costModel = costModel;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    public HejjeOrder create(HejjeOrder order, OrderEventSource source) {
        orders.insert(order);
        orders.appendEvent(order.id(), null, order.state(), source, Map.of(), clock.now());
        return order;
    }

    public void saveIntent(OrderIntent intent) {
        intents.insert(intent);
    }

    public void updateIntentStatus(OrderIntent intent) {
        intents.updateStatus(intent);
    }

    public Optional<OrderIntent> findIntent(UUID id) {
        return intents.findById(id);
    }

    public Optional<HejjeOrder> findById(UUID id) {
        return orders.findById(id);
    }

    public Optional<HejjeOrder> findByTag(ExecutionMode mode, String tag) {
        return orders.findByTag(mode, tag);
    }

    public Optional<HejjeOrder> findByBrokerOrderId(String broker, String brokerOrderId) {
        return orders.findByBrokerOrderId(broker, brokerOrderId);
    }

    public List<HejjeOrder> query(ExecutionMode mode, OrderState state, Instant from, Instant to) {
        return orders.query(mode, state, from, to);
    }

    public List<HejjeOrder> live(ExecutionMode mode) {
        return orders.findLive(mode);
    }

    public List<OrderEvent> events(UUID orderId) {
        return orders.events(orderId);
    }

    public List<Trade> trades(ExecutionMode mode, Instant from, Instant to) {
        return trades.query(mode, from, to);
    }

    public List<Trade> tradesForOrder(UUID orderId) {
        return trades.byOrder(orderId);
    }

    /** The transaction cost of a fill, computed deterministically from the trade and its instrument. */
    public CostBreakdown cost(Trade trade) {
        Instrument instrument = instruments.findById(trade.instrumentId()).orElse(null);
        money.hejje.common.InstrumentType type = instrument == null ? money.hejje.common.InstrumentType.EQ : instrument.type();
        money.hejje.common.Product product = orders.findById(trade.orderId()).map(HejjeOrder::product).orElse(money.hejje.common.Product.MIS);
        return costModel.compute(new CostFill(type, product, trade.side(), trade.quantity(), trade.price()));
    }

    public java.util.Optional<Trade> findTrade(UUID tradeId, ExecutionMode mode) {
        return trades.query(mode, null, null).stream().filter(t -> t.id().equals(tradeId)).findFirst();
    }

    public Optional<Position> findPosition(UUID positionId) {
        return positions.findById(positionId);
    }

    public List<Position> positions(ExecutionMode mode) {
        return positions.byMode(mode);
    }

    public List<Position> openPositions(ExecutionMode mode) {
        return positions.openByMode(mode);
    }

    /**
     * Imports a broker order that Hejje did not create (found during reconciliation). Creates the local order shell, then
     * applies the broker snapshot to record fills and state. Returns the imported order.
     */
    @Transactional
    public HejjeOrder importExternal(String broker, ExecutionMode mode, BrokerOrder update) {
        String tag = "ext-" + update.brokerOrderId();
        UUID id = money.hejje.common.Ids.newId();
        HejjeOrder shell = new HejjeOrder(id, null, mode, broker, update.brokerOrderId(), tag, update.instrumentId(), update.side(),
                update.quantity(), 0, java.math.BigDecimal.ZERO.setScale(2), update.orderType() == null ? money.hejje.common.OrderType.MARKET : update.orderType(),
                update.product() == null ? money.hejje.common.Product.MIS : update.product(),
                update.limitPrice(), update.triggerPrice(), OrderState.BROKER_ACCEPTED, update.rawStatus(),
                update.placedAt() == null ? clock.now() : update.placedAt(), clock.now(), null, null);
        orders.insert(shell);
        orders.appendEvent(id, null, OrderState.BROKER_ACCEPTED, OrderEventSource.EXTERNAL, java.util.Map.of("brokerOrderId", update.brokerOrderId()), clock.now());
        audit.record(AuditEvent.of(AuditEventType.EXTERNAL_ORDER_IMPORTED, ActorType.SYSTEM).withOrderId(id).withBrokerRef(update.brokerOrderId()));
        applyBrokerUpdate(broker, mode, update, OrderEventSource.RECONCILIATION);
        return orders.findById(id).orElseThrow();
    }

    /** Records a user/system-initiated transition (SUBMITTING, CANCEL_PENDING, ...). Throws {@link IllegalTransition}. */
    @Transactional
    public HejjeOrder transition(UUID orderId, OrderState to, OrderEventSource source, Map<String, Object> payload) {
        synchronized (lock(orderId)) {
            HejjeOrder order = orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("No order " + orderId));
            return applyTransition(order, to, source, payload);
        }
    }

    private Object lock(UUID orderId) {
        return locks.computeIfAbsent(orderId, k -> new Object());
    }

    /** Transitions only if the order is currently in {@code expected}; otherwise returns the order unchanged. */
    @Transactional
    public HejjeOrder transitionIfCurrent(UUID orderId, OrderState expected, OrderState to, OrderEventSource source, Map<String, Object> payload) {
        synchronized (lock(orderId)) {
            HejjeOrder order = orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("No order " + orderId));
            if (order.state() != expected) {
                return order;
            }
            return applyTransition(order, to, source, payload);
        }
    }

    private HejjeOrder applyTransition(HejjeOrder order, OrderState to, OrderEventSource source, Map<String, Object> payload) {
        if (order.state() == to) {
            return order;
        }
        if (!OrderStateMachine.isLegal(order.state(), to)) {
            audit.record(AuditEvent.of(AuditEventType.ILLEGAL_TRANSITION, ActorType.SYSTEM).withOrderId(order.id())
                    .withPayload(Map.of("from", order.state().name(), "to", to.name(), "source", source.name())));
            throw new IllegalTransition(order.state(), to);
        }
        OrderState from = order.state();
        HejjeOrder updated = withState(order, to);
        orders.update(updated);
        orders.appendEvent(order.id(), from, to, source, payload, clock.now());
        events.publishEvent(new OrderStateChangedEvent(EventMeta.create(clock), order.id(), from, to, source));
        auditForState(updated, to);
        return updated;
    }

    /** Sets the broker order id on an order still without one (after placeOrder returns). */
    @Transactional
    public HejjeOrder attachBrokerOrderId(UUID orderId, String brokerOrderId, String tag) {
      synchronized (lock(orderId)) {
        HejjeOrder order = orders.findById(orderId).orElseThrow();
        HejjeOrder updated = new HejjeOrder(order.id(), order.intentId(), order.mode(), order.broker(), brokerOrderId, order.tag(),
                order.instrumentId(), order.side(), order.quantity(), order.filledQuantity(), order.averagePrice(), order.orderType(),
                order.product(), order.limitPrice(), order.triggerPrice(), order.state(), order.lastBrokerStatus(),
                order.placedAt() == null ? clock.now() : order.placedAt(), clock.now(), order.parentOrderId(), order.role());
        orders.update(updated);
        return updated;
      }
    }

    /**
     * Applies a broker order update: records incremental fills as trades and into positions, then moves the order state.
     * Idempotent: repeated snapshots of the same cumulative fill do not double-count. Returns the updated order, or empty
     * when no matching Hejje order exists (external orders are handled by reconciliation in M1.6).
     */
    @Transactional
    public Optional<HejjeOrder> applyBrokerUpdate(String broker, ExecutionMode mode, BrokerOrder update, OrderEventSource source) {
        HejjeOrder resolved = resolve(broker, mode, update).orElse(null);
        if (resolved == null) {
            log.debug("Broker update for unknown order {} tag {} (external)", update.brokerOrderId(), update.tag());
            return Optional.empty();
        }
        synchronized (lock(resolved.id())) {
            return applyBrokerUpdateLocked(broker, mode, update, source, resolved.id());
        }
    }

    private Optional<HejjeOrder> applyBrokerUpdateLocked(String broker, ExecutionMode mode, BrokerOrder update, OrderEventSource source, UUID orderId) {
        HejjeOrder order = orders.findById(orderId).orElseThrow();
        if (order.brokerOrderId() == null && update.brokerOrderId() != null) {
            order = attachBrokerOrderId(order.id(), update.brokerOrderId(), order.tag());
        }
        int delta = update.filledQuantity() - order.filledQuantity();
        if (delta > 0) {
            BigDecimal marginalPrice = marginalPrice(order, update, delta);
            recordFill(order, update, delta, marginalPrice, source);
        }
        OrderState target = targetState(update, order);
        HejjeOrder withFills = new HejjeOrder(order.id(), order.intentId(), order.mode(), order.broker(),
                update.brokerOrderId() != null ? update.brokerOrderId() : order.brokerOrderId(), order.tag(), order.instrumentId(),
                order.side(), order.quantity(), update.filledQuantity(), update.averagePrice(), order.orderType(), order.product(),
                order.limitPrice(), order.triggerPrice(), order.state(), update.rawStatus(),
                order.placedAt() == null ? update.placedAt() : order.placedAt(), clock.now(), order.parentOrderId(), order.role());
        if (target != null && order.state() != target && OrderStateMachine.isLegal(order.state(), target)) {
            OrderState from = order.state();
            withFills = withState(withFills, target);
            orders.update(withFills);
            orders.appendEvent(order.id(), from, target, source, Map.of("brokerStatus", String.valueOf(update.rawStatus())), clock.now());
            events.publishEvent(new OrderStateChangedEvent(EventMeta.create(clock), order.id(), from, target, source));
            auditForState(withFills, target);
        } else {
            orders.update(withFills);
        }
        if (delta > 0) {
            boolean complete = withFills.filledQuantity() >= withFills.quantity();
            events.publishEvent(new OrderFilledEvent(EventMeta.create(clock), order.id(), order.instrumentId(),
                    withFills.filledQuantity(), withFills.averagePrice(), complete));
            audit.record(AuditEvent.of(AuditEventType.ORDER_FILLED, ActorType.SYSTEM).withOrderId(order.id())
                    .withPayload(Map.of("filled", withFills.filledQuantity(), "quantity", withFills.quantity(),
                            "averagePrice", withFills.averagePrice().toPlainString())));
        }
        return Optional.of(withFills);
    }

    private void recordFill(HejjeOrder order, BrokerOrder update, int delta, BigDecimal price, OrderEventSource source) {
        // attribution (PRD 53): the strategy on the order's intent; imported/external orders have none and count as MANUAL
        UUID strategyId = order.intentId() == null ? null : intents.findById(order.intentId()).map(OrderIntent::strategyId).orElse(null);
        String brokerTradeId = update.brokerOrderId() + ":" + update.filledQuantity();
        Trade trade = new Trade(Ids.newId(), order.id(), brokerTradeId, order.instrumentId(), order.side(), delta, price, clock.now(),
                order.mode(), strategyId);
        boolean fresh = trades.insertIfAbsent(trade);
        if (fresh) {
            money.hejje.common.Money fee = cost(trade).total();
            positionService.applyFill(order.mode(), order.instrumentId(), order.product(), strategyId, order.side(), delta, price, fee);
        }
    }

    private Optional<HejjeOrder> resolve(String broker, ExecutionMode mode, BrokerOrder update) {
        if (update.brokerOrderId() != null) {
            Optional<HejjeOrder> byId = orders.findByBrokerOrderId(broker, update.brokerOrderId());
            if (byId.isPresent()) {
                return byId;
            }
        }
        if (update.tag() != null && !update.tag().isBlank()) {
            return orders.findByTag(mode, update.tag());
        }
        return Optional.empty();
    }

    private static BigDecimal marginalPrice(HejjeOrder order, BrokerOrder update, int delta) {
        BigDecimal newValue = update.averagePrice().multiply(BigDecimal.valueOf(update.filledQuantity()));
        BigDecimal oldValue = order.averagePrice().multiply(BigDecimal.valueOf(order.filledQuantity()));
        BigDecimal price = newValue.subtract(oldValue).divide(BigDecimal.valueOf(delta), 2, RoundingMode.HALF_UP);
        return price.signum() <= 0 ? update.averagePrice() : price;
    }

    static OrderState targetState(BrokerOrder update, HejjeOrder order) {
        return switch (update.status()) {
            case COMPLETE -> OrderState.FILLED;
            case REJECTED -> OrderState.REJECTED;
            case CANCELLED -> OrderState.CANCELLED;
            case OPEN, PENDING, TRIGGER_PENDING, MODIFY_PENDING, CANCEL_PENDING, UNKNOWN ->
                    update.filledQuantity() > 0 && update.filledQuantity() < order.quantity() ? OrderState.PARTIALLY_FILLED : OrderState.OPEN;
        };
    }

    private void auditForState(HejjeOrder order, OrderState to) {
        switch (to) {
            case BROKER_ACCEPTED -> audit.record(AuditEvent.of(AuditEventType.BROKER_ACCEPTED, ActorType.SYSTEM).withOrderId(order.id())
                    .withBrokerRef(order.brokerOrderId()));
            case CANCELLED -> audit.record(AuditEvent.of(AuditEventType.ORDER_CANCELLED, ActorType.SYSTEM).withOrderId(order.id()));
            case REJECTED -> audit.record(AuditEvent.of(AuditEventType.ORDER_REJECTED, ActorType.SYSTEM).withOrderId(order.id())
                    .withPayload(Map.of("status", String.valueOf(order.lastBrokerStatus()))));
            default -> { }
        }
    }

    private HejjeOrder withState(HejjeOrder o, OrderState state) {
        return new HejjeOrder(o.id(), o.intentId(), o.mode(), o.broker(), o.brokerOrderId(), o.tag(), o.instrumentId(), o.side(),
                o.quantity(), o.filledQuantity(), o.averagePrice(), o.orderType(), o.product(), o.limitPrice(), o.triggerPrice(),
                state, o.lastBrokerStatus(), o.placedAt(), clock.now(), o.parentOrderId(), o.role());
    }
}
