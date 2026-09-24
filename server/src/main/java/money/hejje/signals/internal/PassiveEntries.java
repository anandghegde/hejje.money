package money.hejje.signals.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.Price;
import money.hejje.common.Side;
import money.hejje.common.event.MarketEvent;
import money.hejje.common.event.MarketTick;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ModifyCommand;
import money.hejje.market.MarketService;
import money.hejje.market.QuoteSnapshot;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderService;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalStatus;
import money.hejje.strategy.StrategyDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Passive entries that re-quote (plan M9.8, docs/signals.md "Passive entries"). A {@code limit_touch} entry rests at
 * the best bid (long) or ask (short). When the touch moves away it is modified to the new touch, through the execution
 * engine (rate limiter, executor lease), at most {@code max_requotes} times and never more than {@code max_chase_bps}
 * beyond the signal's price; after {@code cancel_after_seconds} or once the re-quotes are spent and the touch is away
 * again, it is cancelled and the signal ends EXPIRED with {@code ENTRY_NOT_FILLED}. A partial fill keeps its quantity
 * (the position opens with a stop for it) and the rest is cancelled. Quantity is never changed.
 */
@Component
public class PassiveEntries {

    private static final Logger log = LoggerFactory.getLogger(PassiveEntries.class);
    public static final String NOT_FILLED = "ENTRY_NOT_FILLED";

    /** One working passive entry. {@code chaseLimit} is the furthest price a re-quote may reach. */
    record Working(UUID signalId, UUID orderId, UUID instrumentId, Side side, BigDecimal limit, int requotes, int maxRequotes, Instant deadline,
            BigDecimal chaseLimit, BigDecimal tick, UUID strategyId) {

        Working withLimit(BigDecimal newLimit) {
            return new Working(signalId, orderId, instrumentId, side, newLimit, requotes + 1, maxRequotes, deadline, chaseLimit, tick, strategyId);
        }
    }

    private final ExecutionEngine execution;
    private final OrderService orders;
    private final MarketService market;
    private final SignalStore store;
    private final AuditService audit;
    private final TickBus bus;
    private final HejjeClock clock;
    private final Map<UUID, Working> working = new ConcurrentHashMap<>();

    PassiveEntries(ExecutionEngine execution, OrderService orders, MarketService market, SignalStore store, AuditService audit, TickBus bus, HejjeClock clock) {
        this.execution = execution;
        this.orders = orders;
        this.market = market;
        this.store = store;
        this.audit = audit;
        this.bus = bus;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void subscribe() {
        bus.subscribe(this::onMarketEvent);
    }

    /** The limit a passive entry starts at: the touch (bid for a buy, ask for a sell; else the last price), on the tick grid away from a fill. */
    public static BigDecimal touch(Side side, QuoteSnapshot quote, BigDecimal fallback, BigDecimal tick) {
        BigDecimal t = quote == null ? null : side == Side.BUY ? quote.bid() : quote.ask();
        if (t == null || t.signum() <= 0) {
            t = quote != null && quote.lastPrice() != null && quote.lastPrice().signum() > 0 ? quote.lastPrice() : fallback;
        }
        return round(t, tick, side);
    }

    static BigDecimal round(BigDecimal price, BigDecimal tick, Side side) {
        BigDecimal step = tick == null || tick.signum() <= 0 ? new BigDecimal("0.05") : tick;
        return price.divide(step, 0, side == Side.BUY ? RoundingMode.FLOOR : RoundingMode.CEILING).multiply(step).setScale(2, RoundingMode.HALF_UP);
    }

    /** Starts watching a submitted passive entry. */
    public void track(Signal signal, HejjeOrder order, StrategyDefinition.EntryOrder spec, BigDecimal tick) {
        BigDecimal ref = signal.referencePrice();
        BigDecimal chase = spec.maxChaseBps().movePointLeft(4);
        BigDecimal chaseLimit = signal.side() == Side.BUY ? ref.multiply(BigDecimal.ONE.add(chase)) : ref.multiply(BigDecimal.ONE.subtract(chase));
        working.put(order.id(), new Working(signal.id(), order.id(), order.instrumentId(), order.side(), order.limitPrice(), 0, spec.maxRequotes(),
                clock.now().plusSeconds(spec.cancelAfterSeconds()), round(chaseLimit, tick, signal.side()), tick, signal.strategyId()));
    }

    public List<UUID> workingOrders() {
        return List.copyOf(working.keySet());
    }

    private void onMarketEvent(MarketEvent event) {
        if (event instanceof MarketTick tick && !working.isEmpty()) {
            for (Working w : List.copyOf(working.values())) {
                if (w.instrumentId().equals(tick.instrumentId())) {
                    evaluate(w, new QuoteSnapshot(tick.instrumentId(), tick.ts(), tick.lastPrice(), tick.bid(), tick.ask(), tick.volume(), tick.oi(), false));
                }
            }
        }
    }

    /** Deadlines pass without ticks too: every second, each working entry is looked at with the latest quote. */
    @Scheduled(fixedDelayString = "PT1S")
    public void sweep() {
        for (Working w : List.copyOf(working.values())) {
            evaluate(w, market.quote(w.instrumentId()).orElse(null));
        }
    }

    synchronized void evaluate(Working w, QuoteSnapshot quote) {
        if (working.get(w.orderId()) != w) {
            return; // replaced or finished meanwhile
        }
        try {
            Optional<HejjeOrder> found = orders.findById(w.orderId());
            if (found.isEmpty() || found.get().state().isTerminal()) {
                working.remove(w.orderId());
                return;
            }
            HejjeOrder order = found.get();
            if (order.filledQuantity() > 0) {
                giveUp(w, order, "partially filled " + order.filledQuantity() + " of " + order.quantity() + ": the rest is cancelled");
                return;
            }
            if (!clock.now().isBefore(w.deadline())) {
                giveUp(w, order, "not filled within cancel_after_seconds");
                return;
            }
            if (quote == null) {
                return;
            }
            BigDecimal touch = touch(w.side(), quote, w.limit(), w.tick());
            boolean away = w.side() == Side.BUY ? touch.compareTo(w.limit()) > 0 : touch.compareTo(w.limit()) < 0;
            if (!away) {
                return;
            }
            BigDecimal capped = w.side() == Side.BUY ? touch.min(w.chaseLimit()) : touch.max(w.chaseLimit());
            if (capped.compareTo(w.limit()) == 0) {
                return; // at the chase cap: wait for a fill or the deadline
            }
            if (w.requotes() >= w.maxRequotes()) {
                giveUp(w, order, "the touch moved away after " + w.requotes() + " re-quotes (max_requotes)");
                return;
            }
            execution.modify(w.orderId(), new ModifyCommand(null, null, Price.of(capped), null));
            Working next = w.withLimit(capped);
            working.put(w.orderId(), next);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", w.limit().toPlainString());
            payload.put("to", capped.toPlainString());
            payload.put("requote", next.requotes());
            payload.put("maxRequotes", w.maxRequotes());
            audit.record(AuditEvent.of(AuditEventType.ENTRY_REQUOTED, ActorType.STRATEGY).withStrategyId(w.strategyId()).withSignalId(w.signalId())
                    .withOrderId(w.orderId()).withPayload(payload));
        } catch (RuntimeException e) {
            log.warn("Passive entry {} could not be managed: {}", w.orderId(), e.getMessage());
        }
    }

    private void giveUp(Working w, HejjeOrder order, String reason) {
        working.remove(w.orderId());
        try {
            execution.cancel(w.orderId());
        } catch (RuntimeException e) {
            log.warn("Cancelling passive entry {} failed: {}", w.orderId(), e.getMessage());
        }
        if (order.filledQuantity() == 0) {
            store.find(w.signalId()).ifPresent(s -> store.update(s.with(SignalStatus.EXPIRED, NOT_FILLED + ": " + reason, null, null, clock.now())));
        }
        audit.record(AuditEvent.of(AuditEventType.ENTRY_NOT_FILLED, ActorType.STRATEGY).withStrategyId(w.strategyId()).withSignalId(w.signalId())
                .withOrderId(w.orderId()).withPayload(Map.of("reason", reason, "filled", order.filledQuantity(), "quantity", order.quantity(), "limit",
                        w.limit().toPlainString(), "requotes", w.requotes())));
    }
}
