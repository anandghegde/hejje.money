package money.hejje.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.OrderMargin;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.Validity;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.internal.BasketStore;
import money.hejje.execution.internal.OrderWaiter;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Basket execution (PRD 34, plan M5.3). The broker's margin for all legs together is checked first (the basket as a
 * whole); then legs go through the normal pipeline one at a time, hedge legs first, each waited on until filled before
 * the next (explicit legging). ALL_OR_NOTHING stops at the first failed leg and, with CLOSE_FILLED_LEGS, closes the
 * filled legs in reverse order; BEST_EFFORT places every leg. What is not filled by the deadline is cancelled.
 */
@Service
public class BasketService {

    private static final Logger log = LoggerFactory.getLogger(BasketService.class);

    private final ExecutionEngine engine;
    private final BrokerAdapter broker;
    private final BasketStore store;
    private final OrderWaiter waiter;
    private final AuditService audit;
    private final HejjeClock clock;
    private final HejjeProperties properties;
    private final PlanningProperties planning;
    // platform threads: the worker blocks on JDBC, broker calls and sleeps, and must not depend on free virtual-thread carriers
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "basket-worker");
        t.setDaemon(true);
        return t;
    });

    BasketService(ExecutionEngine engine, BrokerAdapter broker, BasketStore store, OrderWaiter waiter, AuditService audit, HejjeClock clock,
            HejjeProperties properties, PlanningProperties planning) {
        this.engine = engine;
        this.broker = broker;
        this.store = store;
        this.waiter = waiter;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
        this.planning = planning;
    }

    /** Records the basket (idempotent by client and key), checks the combined margin and starts executing it. */
    public Basket submit(BasketCommand c) {
        Optional<Basket> existing = store.findByKey(c.clientId(), c.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (c.legs().isEmpty() || c.legs().size() > planning.maxBasketLegs()) {
            throw new IllegalArgumentException("A basket needs 1 to " + planning.maxBasketLegs() + " legs");
        }
        for (BasketCommand.Leg leg : c.legs()) {
            if (leg.instrumentId() == null || leg.side() == null || leg.quantity() < 1 || leg.orderType() == null || leg.product() == null) {
                throw new IllegalArgumentException("Every leg needs an instrument, side, quantity of at least 1, order type and product");
            }
        }
        Instant now = clock.now();
        Duration deadline = c.deadline() == null ? planning.basketDeadline() : c.deadline();
        Money required = null;
        Money available = null;
        String detail = null;
        Basket.Status status = Basket.Status.PENDING;
        try {
            List<OrderMargin> margins = broker.getOrderMargins(c.legs().stream().map(l -> new BrokerOrderRequest(l.instrumentId(), l.side(),
                    Quantity.of(l.quantity()), l.orderType(), l.product(), l.limitPrice(), l.triggerPrice(), Validity.DAY, "basketmargin")).toList());
            required = margins.stream().map(OrderMargin::total).reduce(Money.ZERO, Money::plus);
            available = broker.getFunds().availableCash();
            if (required.paise() > available.paise()) {
                status = Basket.Status.FAILED;
                detail = "basket margin " + required.toRupeesString() + " exceeds available funds " + available.toRupeesString() + "; no leg was placed";
            }
        } catch (RuntimeException e) {
            detail = "basket margin unavailable (" + e.getMessage() + "); legs are risk-checked one by one";
        }
        UUID id = Ids.newId();
        List<BasketLeg> legs = new ArrayList<>();
        for (int i = 0; i < c.legs().size(); i++) {
            BasketCommand.Leg l = c.legs().get(i);
            legs.add(new BasketLeg(Ids.newId(), i + 1, l.hedgeFirst(), null, l.instrumentId(), l.side(), l.quantity(), l.orderType(), l.product(), value(l.limitPrice()),
                    value(l.triggerPrice()), value(l.stopPrice()), value(l.targetPrice()), null,
                    status == Basket.Status.FAILED ? BasketLeg.Status.SKIPPED : BasketLeg.Status.PENDING, null, null));
        }
        ActorType source = c.source() == null ? ActorType.USER : c.source();
        Basket basket = new Basket(id, properties.mode(), c.name(), c.clientId(), source, c.actorId(), c.strategyId(),
                c.reason() == null ? OrderReason.MANUAL : c.reason(), c.policy() == null ? Basket.Policy.ALL_OR_NOTHING : c.policy(),
                c.rollback() == null ? Basket.Rollback.NONE : c.rollback(), now.plus(deadline), status, required, available, detail, now, now, legs);
        try {
            store.insert(basket, c.idempotencyKey());
        } catch (DuplicateKeyException e) {
            return store.findByKey(c.clientId(), c.idempotencyKey()).orElseThrow();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("basketId", id.toString());
        payload.put("legs", legs.size());
        payload.put("policy", basket.policy().name());
        payload.put("rollback", basket.rollback().name());
        payload.put("deadline", basket.deadline().toString());
        if (required != null) {
            payload.put("marginRequired", required.toRupeesString());
            payload.put("marginAvailable", available.toRupeesString());
        }
        audit.record(AuditEvent.of(AuditEventType.BASKET_CREATED, source).withActorId(c.actorId()).withStrategyId(c.strategyId()).withPayload(payload));
        if (status == Basket.Status.PENDING) {
            worker.submit(() -> execute(id));
        } else {
            recordFinished(basket, status, detail);
        }
        return store.find(id).orElseThrow();
    }

    void execute(UUID id) {
        try {
            run(id);
        } catch (RuntimeException e) {
            log.warn("Basket {} failed", id, e);
            if (store.finish(id, Basket.Status.FAILED, "error: " + e.getMessage(), clock.now())) {
                store.find(id).ifPresent(b -> recordFinished(b, Basket.Status.FAILED, "error: " + e.getMessage()));
            }
        }
    }

    private void run(UUID id) {
        Basket b = store.find(id).orElseThrow();
        if (!store.transition(id, Basket.Status.PENDING, Basket.Status.EXECUTING, clock.now())) {
            return;
        }
        List<BasketLeg> ordered = b.legs().stream().sorted(Comparator.comparing((BasketLeg l) -> !l.hedgeFirst()).thenComparingInt(BasketLeg::sequence)).toList();
        List<HejjeOrder> filled = new ArrayList<>();
        List<BasketLeg> filledLegs = new ArrayList<>();
        boolean failed = false;
        boolean expired = false;
        int placed = 0;
        for (BasketLeg leg : ordered) {
            if (failed && b.policy() == Basket.Policy.ALL_OR_NOTHING) {
                store.updateLeg(leg.id(), BasketLeg.Status.SKIPPED, null, "not placed: an earlier leg failed", null, null);
                continue;
            }
            if (clock.now().isAfter(b.deadline())) {
                store.updateLeg(leg.id(), BasketLeg.Status.SKIPPED, null, "not placed: the deadline passed", null, null);
                failed = true;
                expired = true;
                continue;
            }
            HejjeOrder order;
            try {
                order = engine.submit(legCommand(b, leg));
            } catch (RuntimeException e) {
                store.updateLeg(leg.id(), BasketLeg.Status.FAILED, null, "refused: " + ExecutionException.describe(e), placed++, null);
                failed = true;
                continue;
            }
            store.updateLeg(leg.id(), BasketLeg.Status.SUBMITTED, order.id(), null, placed++, null);
            HejjeOrder done = waiter.await(order.id(), b.deadline());
            if (done.state() == OrderState.FILLED) {
                store.updateLeg(leg.id(), BasketLeg.Status.FILLED, null, null, null, null);
                filled.add(done);
                filledLegs.add(leg);
            } else if (!done.state().isTerminal()) {
                try {
                    engine.cancel(done.id());
                } catch (RuntimeException e) {
                    log.warn("Cancel of basket leg {} failed: {}", done.id(), e.getMessage());
                }
                HejjeOrder after = waiter.await(done.id(), Instant.MAX);
                store.updateLeg(leg.id(), BasketLeg.Status.CANCELLED, null, "cancelled at the deadline with " + after.filledQuantity() + " filled", null, null);
                if (after.filledQuantity() > 0) {
                    filled.add(after);
                    filledLegs.add(leg);
                }
                failed = true;
                expired = true;
            } else {
                store.updateLeg(leg.id(), BasketLeg.Status.FAILED, null, "order " + done.state() + (done.lastBrokerStatus() == null ? "" : " (" + done.lastBrokerStatus() + ")"),
                        null, null);
                failed = true;
            }
        }
        Basket.Status status;
        String detail;
        if (!failed) {
            status = Basket.Status.COMPLETED;
            detail = "all " + ordered.size() + " legs filled";
        } else if (b.policy() == Basket.Policy.BEST_EFFORT) {
            status = filled.isEmpty() ? Basket.Status.FAILED : Basket.Status.PARTIAL;
            detail = filled.size() + " of " + ordered.size() + " legs filled";
        } else if (b.rollback() == Basket.Rollback.CLOSE_FILLED_LEGS && !filled.isEmpty()) {
            int closed = rollback(b, filledLegs, filled);
            status = closed == filled.size() ? Basket.Status.ROLLED_BACK : Basket.Status.FAILED;
            detail = (expired ? "the deadline passed" : "a leg failed") + "; closed " + closed + " of " + filled.size() + " filled leg(s)";
        } else {
            status = expired ? Basket.Status.EXPIRED : Basket.Status.FAILED;
            detail = (expired ? "the deadline passed" : "a leg failed") + (filled.isEmpty() ? "; no leg filled" : "; " + filled.size() + " filled leg(s) left open");
        }
        if (store.finish(id, status, detail, clock.now())) {
            recordFinished(store.find(id).orElse(b), status, detail);
        }
    }

    /** Closes filled legs in reverse placement order (hedges last); returns how many closing orders were accepted. */
    private int rollback(Basket b, List<BasketLeg> legs, List<HejjeOrder> fills) {
        int closed = 0;
        for (int i = legs.size() - 1; i >= 0; i--) {
            BasketLeg leg = legs.get(i);
            HejjeOrder fill = fills.get(i);
            Side exit = leg.side() == Side.BUY ? Side.SELL : Side.BUY;
            try {
                HejjeOrder close = engine.submit(new OrderIntentCommand(b.clientId(), "basket:" + b.id() + ":rollback:" + leg.sequence(), b.source(), b.actorId(),
                        b.strategyId(), null, leg.instrumentId(), exit, Quantity.of(fill.filledQuantity()), OrderType.MARKET, leg.product(), null, null, null, null, null,
                        OrderReason.BASKET_ROLLBACK));
                store.updateLeg(leg.id(), BasketLeg.Status.ROLLED_BACK, null, "closed by the rollback", null, close.id());
                closed++;
            } catch (RuntimeException e) {
                store.updateLeg(leg.id(), BasketLeg.Status.FILLED, null, "rollback refused: " + ExecutionException.describe(e), null, null);
            }
        }
        return closed;
    }

    private OrderIntentCommand legCommand(Basket b, BasketLeg leg) {
        return new OrderIntentCommand(b.clientId(), "basket:" + b.id() + ":" + leg.sequence(), b.source(), b.actorId(), b.strategyId(), null, leg.instrumentId(),
                leg.side(), Quantity.of(leg.quantity()), leg.orderType(), leg.product(), price(leg.limitPrice()), price(leg.triggerPrice()), price(leg.stopPrice()),
                price(leg.targetPrice()), null, b.reason());
    }

    private void recordFinished(Basket b, Basket.Status status, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("basketId", b.id().toString());
        payload.put("status", status.name());
        payload.put("detail", detail == null ? "" : detail);
        payload.put("legs", b.legs().stream().map(l -> l.sequence() + ":" + l.status()).toList());
        audit.record(AuditEvent.of(AuditEventType.BASKET_FINISHED, b.source()).withActorId(b.actorId()).withStrategyId(b.strategyId()).withPayload(payload));
    }

    public Optional<Basket> find(UUID id) {
        return store.find(id);
    }

    public List<Basket> list(int limit) {
        return store.list(properties.mode(), Math.max(1, Math.min(limit, 200)));
    }

    @EventListener(ApplicationReadyEvent.class)
    void failInterrupted() {
        int n = store.failExecuting("interrupted by a restart; legs already placed are ordinary orders", clock.now());
        if (n > 0) {
            log.warn("Marked {} basket(s) interrupted by the restart as FAILED", n);
        }
    }

    private static java.math.BigDecimal value(Price p) {
        return p == null ? null : p.value();
    }

    private static Price price(java.math.BigDecimal v) {
        return v == null ? null : Price.of(v);
    }
}
