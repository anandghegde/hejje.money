package money.hejje.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.Quote;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.Price;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.internal.OrderValidator;
import money.hejje.execution.internal.OrderWaiter;
import money.hejje.execution.internal.SplitStore;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.IntentStatus;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.risk.RiskDecision;
import money.hejje.risk.RiskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Split orders (PRD 35, plan M5.3). The whole intent is validated and risk-checked once (a split is an execution
 * tactic, never a way around a limit); children of at most {@code maxChildQuantity} then go through the normal pipeline
 * one at a time, {@code delayMs} apart, each waited on before the next, until filled, the price moves beyond the
 * tolerance, the deadline passes or a child fails.
 */
@Service
public class SplitService {

    private static final Logger log = LoggerFactory.getLogger(SplitService.class);

    private final ExecutionEngine engine;
    private final OrderService orders;
    private final OrderValidator validator;
    private final RiskEngine risk;
    private final BrokerAdapter broker;
    private final InstrumentService instruments;
    private final SplitStore store;
    private final OrderWaiter waiter;
    private final AuditService audit;
    private final HejjeClock clock;
    private final HejjeProperties properties;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    SplitService(ExecutionEngine engine, OrderService orders, OrderValidator validator, RiskEngine risk, BrokerAdapter broker, InstrumentService instruments,
            SplitStore store, OrderWaiter waiter, AuditService audit, HejjeClock clock, HejjeProperties properties) {
        this.engine = engine;
        this.orders = orders;
        this.validator = validator;
        this.risk = risk;
        this.broker = broker;
        this.instruments = instruments;
        this.store = store;
        this.waiter = waiter;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
    }

    /** Validates and risk-checks the whole intent, records the split and starts working it; idempotent by client and key. */
    public SplitOrder start(OrderIntentCommand c, SplitPolicy policy) {
        Optional<SplitOrder> existing = store.findByKey(c.clientId(), c.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        int lot = instruments.findById(c.instrumentId()).map(Instrument::lotSize).orElse(1);
        int child = policy.maxChildQuantity() / lot * lot;
        if (child < lot) {
            throw new ExecutionException.Validation(List.of("maxChildQuantity " + policy.maxChildQuantity() + " is below one lot (" + lot + ")"));
        }
        ActorType source = c.source() == null ? ActorType.USER : c.source();
        OrderReason reason = c.reason() == null ? OrderReason.MANUAL : c.reason();
        Instant now = clock.now();
        OrderIntent whole = new OrderIntent(Ids.newId(), c.idempotencyKey(), c.clientId(), source, c.actorId(), c.strategyId(), c.signalId(), c.instrumentId(),
                c.side(), c.quantity(), c.orderType(), c.product(), c.limitPrice(), c.triggerPrice(), c.stopPrice(), c.targetPrice(), c.maxRisk(), reason,
                properties.mode(), IntentStatus.CREATED, List.of(), now);
        List<String> errors = validator.validate(whole);
        if (!errors.isEmpty()) {
            throw new ExecutionException.Validation(errors);
        }
        RiskDecision decision = risk.evaluate(whole);
        if (!decision.isApproved()) {
            throw new ExecutionException.RiskRejected(decision.checks());
        }
        BigDecimal reference = c.limitPrice() != null ? c.limitPrice().value() : lastPrice(c.instrumentId()).orElse(null);
        SplitOrder s = new SplitOrder(Ids.newId(), properties.mode(), c.clientId(), source, c.actorId(), c.strategyId(), c.instrumentId(), c.side(),
                c.quantity().value(), c.orderType(), c.product(), value(c.limitPrice()), value(c.triggerPrice()), value(c.stopPrice()), value(c.targetPrice()),
                reason, policy, SplitOrder.Status.WORKING, 0, 0, reference, now.plusSeconds(policy.deadlineSeconds()), null, now, now);
        try {
            store.insert(s, c.idempotencyKey());
        } catch (DuplicateKeyException e) {
            return store.findByKey(c.clientId(), c.idempotencyKey()).orElseThrow();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("splitId", s.id().toString());
        payload.put("instrumentId", s.instrumentId().toString());
        payload.put("side", s.side().name());
        payload.put("quantity", s.quantity());
        payload.put("maxChildQuantity", child);
        payload.put("delayMs", policy.delayMs());
        payload.put("deadline", s.deadline().toString());
        audit.record(AuditEvent.of(AuditEventType.SPLIT_STARTED, source).withActorId(s.actorId()).withStrategyId(s.strategyId()).withPayload(payload));
        worker.submit(() -> run(s.id(), child));
        return s;
    }

    void run(UUID id, int childQuantity) {
        try {
            int sequence = 0;
            while (true) {
                SplitOrder s = store.find(id).orElseThrow();
                if (s.status() != SplitOrder.Status.WORKING) {
                    return;
                }
                int remaining = s.quantity() - s.filledQuantity();
                if (remaining <= 0) {
                    finish(s, SplitOrder.Status.COMPLETED, s.children() + " child order(s) filled");
                    return;
                }
                if (clock.now().isAfter(s.deadline())) {
                    finish(s, SplitOrder.Status.EXPIRED, "deadline passed with " + s.filledQuantity() + " of " + s.quantity() + " filled");
                    return;
                }
                String moved = adverseMove(s);
                if (moved != null) {
                    if (s.policy().cancelOnMove()) {
                        finish(s, SplitOrder.Status.CANCELLED, moved + "; " + s.filledQuantity() + " of " + s.quantity() + " filled");
                        return;
                    }
                    OrderWaiter.sleep(Math.max(250, s.policy().delayMs()));
                    continue;
                }
                int quantity = Math.min(childQuantity, remaining);
                HejjeOrder child;
                try {
                    // children continue one intent that already passed the re-entry cooldown as a whole; every other check applies to each child
                    child = engine.submit(childCommand(s, quantity, sequence), java.util.Set.of("reentryCooldown"), "child of split " + s.id()
                            + ", whose whole intent passed the cooldown");
                } catch (RuntimeException e) {
                    finish(s, SplitOrder.Status.FAILED, "child " + (sequence + 1) + " refused: " + ExecutionException.describe(e));
                    return;
                }
                orders.setParent(child.id(), s.id());
                store.childPlaced(s.id(), clock.now());
                HejjeOrder done = waiter.await(child.id(), s.deadline());
                if (!done.state().isTerminal()) {
                    try {
                        engine.cancel(done.id());
                    } catch (RuntimeException e) {
                        log.warn("Cancel of split child {} failed: {}", done.id(), e.getMessage());
                    }
                    done = waiter.await(done.id(), Instant.MAX);
                    store.addFilled(s.id(), done.filledQuantity(), clock.now());
                    finish(store.find(id).orElseThrow(), SplitOrder.Status.EXPIRED, "child " + (sequence + 1) + " was still working at the deadline and was cancelled");
                    return;
                }
                store.addFilled(s.id(), done.filledQuantity(), clock.now());
                if (done.state() != OrderState.FILLED) {
                    finish(store.find(id).orElseThrow(), SplitOrder.Status.FAILED, "child " + (sequence + 1) + " ended " + done.state());
                    return;
                }
                sequence++;
                if (remaining - quantity > 0) {
                    OrderWaiter.sleep(s.policy().delayMs());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Split {} failed", id, e);
            store.find(id).ifPresent(s -> finish(s, SplitOrder.Status.FAILED, "error: " + e.getMessage()));
        }
    }

    private OrderIntentCommand childCommand(SplitOrder s, int quantity, int sequence) {
        return new OrderIntentCommand(s.clientId(), "split:" + s.id() + ":" + sequence, s.source(), s.actorId(), s.strategyId(), null, s.instrumentId(), s.side(),
                Quantity.of(quantity), s.orderType(), s.product(), price(s.limitPrice()), price(s.triggerPrice()), price(s.stopPrice()), price(s.targetPrice()), null,
                s.reason());
    }

    /** Null while within the tolerance (or without one), else why the price is too far from the reference. */
    String adverseMove(SplitOrder s) {
        if (s.policy().priceTolerancePct() == null || s.referencePrice() == null || s.referencePrice().signum() <= 0) {
            return null;
        }
        Optional<BigDecimal> last = lastPrice(s.instrumentId());
        if (last.isEmpty()) {
            return null;
        }
        BigDecimal diff = s.side() == Side.BUY ? last.get().subtract(s.referencePrice()) : s.referencePrice().subtract(last.get());
        BigDecimal pct = diff.multiply(BigDecimal.valueOf(100)).divide(s.referencePrice(), 4, RoundingMode.HALF_UP);
        return pct.compareTo(s.policy().priceTolerancePct()) > 0
                ? "price moved " + pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "% against the order from " + s.referencePrice().toPlainString()
                        + " (tolerance " + s.policy().priceTolerancePct().stripTrailingZeros().toPlainString() + "%)"
                : null;
    }

    private boolean finish(SplitOrder s, SplitOrder.Status status, String detail) {
        if (!store.finish(s.id(), status, detail, clock.now())) {
            return false;
        }
        SplitOrder done = store.find(s.id()).orElse(s);
        audit.record(AuditEvent.of(AuditEventType.SPLIT_FINISHED, s.source()).withActorId(s.actorId()).withStrategyId(s.strategyId())
                .withPayload(Map.of("splitId", s.id().toString(), "status", status.name(), "filled", done.filledQuantity(), "quantity", s.quantity(),
                        "children", done.children(), "detail", detail)));
        return true;
    }

    /** Stops a working split: no more children, and a child still working is cancelled. */
    public SplitOrder cancel(UUID id, String by) {
        SplitOrder s = store.find(id).orElseThrow(() -> new NoSuchElementException("No split " + id));
        if (s.status() == SplitOrder.Status.WORKING && finish(s, SplitOrder.Status.CANCELLED, "cancelled by " + by)) {
            for (HejjeOrder child : orders.childrenOf(id)) {
                if (child.state().isLive()) {
                    try {
                        engine.cancel(child.id());
                    } catch (RuntimeException e) {
                        log.warn("Cancel of split child {} failed: {}", child.id(), e.getMessage());
                    }
                }
            }
        }
        return store.find(id).orElseThrow();
    }

    public Optional<SplitOrder> find(UUID id) {
        return store.find(id);
    }

    public List<SplitOrder> list(int limit) {
        return store.list(properties.mode(), Math.max(1, Math.min(limit, 200)));
    }

    public List<HejjeOrder> children(UUID id) {
        return orders.childrenOf(id);
    }

    @EventListener(ApplicationReadyEvent.class)
    void failInterrupted() {
        int n = store.failWorking("interrupted by a restart; children already placed are ordinary orders", clock.now());
        if (n > 0) {
            log.warn("Marked {} split order(s) interrupted by the restart as FAILED", n);
        }
    }

    private Optional<BigDecimal> lastPrice(UUID instrumentId) {
        try {
            return broker.getQuote(Set.of(instrumentId)).stream().findFirst().map(Quote::lastPrice).filter(p -> p != null && p.signum() > 0);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static BigDecimal value(Price p) {
        return p == null ? null : p.value();
    }

    private static Price price(BigDecimal v) {
        return v == null ? null : Price.of(v);
    }
}
