package money.hejje.options;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.Basket;
import money.hejje.execution.BasketLeg;
import money.hejje.execution.BasketService;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.market.MarketService;
import money.hejje.options.internal.OptionsStore;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Manages options positions (plan M5.4): PENDING until the opening basket completes (entry premiums from the fills),
 * then OPEN positions are checked against the force-exit time, the underlying's stop, each leg's premium stop/target and
 * the combined P&L stop/target; the first exit met closes every leg — short legs are bought back before long legs are
 * sold — with exposure-reducing market orders.
 */
@Component
public class OptionsPositionMonitor {

    private static final Logger log = LoggerFactory.getLogger(OptionsPositionMonitor.class);
    static final int MAX_EXIT_ATTEMPTS = 3;

    private final OptionsStore store;
    private final BasketService baskets;
    private final OrderService orders;
    private final ExecutionEngine engine;
    private final MarketService market;
    private final AuditService audit;
    private final HejjeClock clock;
    private final HejjeProperties properties;

    OptionsPositionMonitor(OptionsStore store, BasketService baskets, OrderService orders, ExecutionEngine engine, MarketService market, AuditService audit,
            HejjeClock clock, HejjeProperties properties) {
        this.store = store;
        this.baskets = baskets;
        this.orders = orders;
        this.engine = engine;
        this.market = market;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${hejje.options.monitor-interval:PT5S}", initialDelayString = "PT30S")
    public synchronized void tick() {
        for (OptionsPosition p : store.active(properties.mode())) {
            try {
                switch (p.status()) {
                    case PENDING -> pending(p);
                    case OPEN -> {
                        String reason = exitReason(p);
                        if (reason != null) {
                            OptionsPosition closing = p.with(OptionsPosition.Status.CLOSING, p.legs(), reason, null, p.detail(), null, clock.now());
                            store.update(closing);
                            closing(closing);
                        }
                    }
                    case CLOSING -> closing(p);
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Options position {} step failed", p.id(), e);
            }
        }
    }

    private void pending(OptionsPosition p) {
        Optional<Basket> basket = baskets.find(p.basketId());
        if (basket.isEmpty()) {
            return;
        }
        Basket b = basket.get();
        switch (b.status()) {
            case COMPLETED -> {
                List<OptionsPosition.Leg> legs = new ArrayList<>();
                for (OptionsPosition.Leg leg : p.legs()) {
                    BasketLeg bl = b.legs().stream().filter(x -> x.sequence() == leg.sequence()).findFirst().orElseThrow();
                    HejjeOrder order = orders.findById(bl.orderId()).orElseThrow();
                    legs.add(leg.withEntry(order.averagePrice(), order.id()));
                }
                OptionsPosition open = p.with(OptionsPosition.Status.OPEN, legs, null, null, null, null, clock.now());
                store.update(open);
                Map<String, Object> payload = payload(open);
                payload.put("entries", legs.stream().map(l -> l.symbol() + " " + l.side() + " " + l.quantity() + " @ " + l.entryPrice().toPlainString()).toList());
                audit.record(AuditEvent.of(AuditEventType.OPTIONS_POSITION_OPENED, p.source()).withActorId(p.actorId()).withStrategyId(p.strategyId())
                        .withSignalId(p.signalId()).withPayload(payload));
            }
            case FAILED, ROLLED_BACK, EXPIRED, PARTIAL -> store.update(p.with(OptionsPosition.Status.FAILED, p.legs(), "BASKET_" + b.status(), null,
                    b.detail(), clock.now(), clock.now()));
            default -> {
            }
        }
    }

    /** The first exit condition met, or null. */
    String exitReason(OptionsPosition p) {
        if (!clock.nowIst().toLocalTime().isBefore(p.forceExitTime())) {
            return "FORCE_EXIT";
        }
        if (p.underlyingStop() != null) {
            Optional<BigDecimal> u = market.lastPrice(p.underlyingInstrumentId());
            if (p.neutral()) {
                // a neutral position's band: the underlying leaving it either way closes the position (plan M6.4)
                if (u.isPresent() && (u.get().compareTo(p.underlyingStop()) <= 0 || p.underlyingStopHigh() != null && u.get().compareTo(p.underlyingStopHigh()) >= 0)) {
                    return "UNDERLYING_BAND";
                }
            } else if (u.isPresent() && (p.direction() == Side.BUY ? u.get().compareTo(p.underlyingStop()) <= 0 : u.get().compareTo(p.underlyingStop()) >= 0)) {
                return "UNDERLYING_STOP";
            }
        }
        BigDecimal combined = BigDecimal.ZERO;
        boolean allPriced = true;
        for (OptionsPosition.Leg leg : p.legs()) {
            Optional<BigDecimal> last = market.lastPrice(leg.instrumentId());
            if (last.isEmpty()) {
                allPriced = false;
                continue;
            }
            BigDecimal price = last.get();
            boolean buy = leg.side() == Side.BUY;
            if (leg.stopPrice() != null && (buy ? price.compareTo(leg.stopPrice()) <= 0 : price.compareTo(leg.stopPrice()) >= 0)) {
                return "LEG_STOP";
            }
            if (leg.targetPrice() != null && (buy ? price.compareTo(leg.targetPrice()) >= 0 : price.compareTo(leg.targetPrice()) <= 0)) {
                return "LEG_TARGET";
            }
            combined = combined.add(leg.pnlAt(price));
        }
        if (allPriced) {
            if (p.combinedStop() != null && combined.compareTo(p.combinedStop().toRupees().negate()) <= 0) {
                return "COMBINED_STOP";
            }
            if (p.combinedTarget() != null && combined.compareTo(p.combinedTarget().toRupees()) >= 0) {
                return "COMBINED_TARGET";
            }
        }
        return null;
    }

    /** Submits exits (short legs first, long legs once every short is flat) and completes the position when all are filled. */
    private void closing(OptionsPosition p) {
        List<OptionsPosition.Leg> exitOrder = p.legs().stream()
                .sorted(Comparator.comparing((OptionsPosition.Leg l) -> l.side() == Side.BUY).thenComparingInt(OptionsPosition.Leg::sequence)).toList();
        Map<Integer, OptionsPosition.Leg> updated = new LinkedHashMap<>();
        p.legs().forEach(l -> updated.put(l.sequence(), l));
        boolean shortsFlat = true;
        boolean failedExit = false;
        for (OptionsPosition.Leg leg : exitOrder) {
            OptionsPosition.Leg current = updated.get(leg.sequence());
            if (leg.side() == Side.BUY && !shortsFlat) {
                continue; // a long leg is the protection of a short one: sold only after the shorts are bought back
            }
            if (current.exitOrderId() != null) {
                HejjeOrder exit = orders.findById(current.exitOrderId()).orElseThrow();
                if (exit.state() == OrderState.FILLED) {
                    updated.put(leg.sequence(), current.withExit(exit.id(), exit.averagePrice(), current.exitAttempts()));
                    continue;
                }
                if (!exit.state().isTerminal()) {
                    if (leg.side() == Side.SELL) {
                        shortsFlat = false;
                    }
                    continue;
                }
                if (current.exitAttempts() >= MAX_EXIT_ATTEMPTS) {
                    failedExit = true;
                    if (leg.side() == Side.SELL) {
                        shortsFlat = false;
                    }
                    continue;
                }
            }
            current = submitExit(p, current);
            updated.put(leg.sequence(), current);
            if (leg.side() == Side.SELL && (current.exitOrderId() == null || !orders.findById(current.exitOrderId()).map(o -> o.state() == OrderState.FILLED).orElse(false))) {
                shortsFlat = false;
            }
        }
        List<OptionsPosition.Leg> legs = new ArrayList<>(updated.values());
        boolean done = legs.stream().allMatch(l -> l.exitPrice() != null);
        if (done) {
            BigDecimal realized = legs.stream().map(l -> l.pnlAt(l.exitPrice())).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            OptionsPosition closed = p.with(OptionsPosition.Status.CLOSED, legs, p.closeReason(), Money.of(realized), p.detail(), clock.now(), clock.now());
            store.update(closed);
            Map<String, Object> payload = payload(closed);
            payload.put("reason", p.closeReason());
            payload.put("realized", realized.toPlainString());
            audit.record(AuditEvent.of(AuditEventType.OPTIONS_POSITION_CLOSED, p.source()).withActorId(p.actorId()).withStrategyId(p.strategyId())
                    .withSignalId(p.signalId()).withPayload(payload));
        } else {
            store.update(p.with(OptionsPosition.Status.CLOSING, legs, p.closeReason(), null,
                    failedExit ? "an exit order was refused " + MAX_EXIT_ATTEMPTS + " times; close the remaining legs by hand" : p.detail(), null, clock.now()));
        }
    }

    private OptionsPosition.Leg submitExit(OptionsPosition p, OptionsPosition.Leg leg) {
        int attempt = leg.exitAttempts() + 1;
        try {
            HejjeOrder order = engine.submit(new OrderIntentCommand(p.clientId(), "optclose:" + p.id() + ":" + leg.sequence() + ":" + attempt, p.source(), p.actorId(),
                    p.strategyId(), p.signalId(), leg.instrumentId(), leg.side() == Side.BUY ? Side.SELL : Side.BUY, Quantity.of(leg.quantity()), OrderType.MARKET,
                    p.product(), null, null, null, null, null, OrderReason.STRATEGY_EXIT));
            BigDecimal price = order.state() == OrderState.FILLED ? order.averagePrice() : null;
            return leg.withExit(order.id(), price, attempt);
        } catch (RuntimeException e) {
            log.warn("Exit of {} leg {} refused: {}", p.id(), leg.sequence(), e.getMessage());
            return leg.withExit(leg.exitOrderId(), null, attempt);
        }
    }

    private static Map<String, Object> payload(OptionsPosition p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("optionsPositionId", p.id().toString());
        m.put("basketId", p.basketId().toString());
        m.put("underlying", p.underlying());
        m.put("direction", p.neutral() ? "NEUTRAL" : p.direction().name());
        return m;
    }
}
