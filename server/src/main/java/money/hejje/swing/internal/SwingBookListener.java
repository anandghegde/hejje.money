package money.hejje.swing.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.Optional;
import money.hejje.common.Ids;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.time.HejjeClock;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.orders.PositionChangedEvent;
import money.hejje.orders.Trade;
import money.hejje.swing.SwingPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the swing book in step with the delivery (CNC) positions (plan M11.1): a CNC position that goes long opens a swing
 * round trip (entry date, stop and goal from the entry order's intent), a change of its quantity updates it, and its
 * return to zero closes it with the exit price and the sessions held.
 */
@Component
class SwingBookListener {

    private static final Logger log = LoggerFactory.getLogger(SwingBookListener.class);
    /** How far back the fills of an open swing round trip are looked for. */
    private static final java.time.Duration LOOKBACK = java.time.Duration.ofDays(400);

    private final OrderService orders;
    private final SwingStore store;
    private final HejjeClock clock;

    private final money.hejje.swing.SwingEntries entries;

    private final money.hejje.execution.GttService gtts;
    private final org.springframework.context.ApplicationEventPublisher events;

    SwingBookListener(OrderService orders, SwingStore store, HejjeClock clock, money.hejje.swing.SwingEntries entries, money.hejje.execution.GttService gtts,
            org.springframework.context.ApplicationEventPublisher events) {
        this.gtts = gtts;
        this.events = events;
        this.entries = entries;
        this.orders = orders;
        this.store = store;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void onPositionChanged(PositionChangedEvent event) {
        try {
            orders.findPosition(event.positionId()).filter(p -> p.product() == Product.CNC).ifPresent(this::apply);
        } catch (RuntimeException e) {
            log.warn("Swing book update for position {} failed", event.positionId(), e);
        }
    }

    /** Brings the swing book in line with the position's current state (idempotent). */
    synchronized void apply(Position p) {
        Instant now = clock.now();
        Optional<SwingPosition> open = store.findOpen(p.id());
        if (p.netQuantity() > 0) {
            if (open.isPresent()) {
                store.updateOpen(open.get().id(), p.netQuantity(), p.averagePrice(), now);
                return;
            }
            List<Trade> fills = fills(p, now);
            Trade entry = lastEntryFill(fills);
            if (entry == null) {
                log.warn("Delivery position {} is long but no entry fill was found", p.id());
                return;
            }
            Optional<OrderIntent> intent = orders.findById(entry.orderId()).flatMap(o -> o.intentId() == null ? Optional.empty() : orders.findIntent(o.intentId()));
            BigDecimal stop = intent.map(OrderIntent::stopPrice).map(s -> s.value()).orElse(null);
            BigDecimal goal = intent.map(OrderIntent::targetPrice).map(t -> t.value()).orElse(null);
            // a swing deployment's positions trail when its params say so (plan M11.4); manual ones do not
            boolean trail = entries.deploymentOfStrategy(p.strategyId()).map(entries::trail).orElse(false);
            UUID id = Ids.newId();
            boolean inserted = store.insertOpen(new SwingPosition(id, p.mode(), p.id(), p.instrumentId(), p.strategyId(), entry.orderId(), entry.ts(),
                    entry.ts().atZone(clock.zone()).toLocalDate(), p.netQuantity(), p.averagePrice(), stop, goal, SwingPosition.Status.OPEN, null, null, null,
                    null, trail), now);
            if (inserted) {
                notify("ENTRY_FILLED", id, p.instrumentId(), Map.of("quantity", p.netQuantity(), "entry", p.averagePrice().toPlainString(),
                        "stop", stop == null ? "none" : stop.toPlainString(), "goal", goal == null ? "none" : goal.toPlainString()));
            }
        } else if (open.isPresent()) {
            SwingPosition s = open.get();
            // the exit: the delivery sells since the entry
            List<Trade> sells = fills(p, now).stream().filter(t -> t.side() == Side.SELL && !t.ts().isBefore(s.openedAt())).toList();
            BigDecimal exit = null;
            int qty = sells.stream().mapToInt(Trade::quantity).sum();
            if (qty > 0) {
                BigDecimal value = sells.stream().map(t -> t.price().multiply(BigDecimal.valueOf(t.quantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
                exit = value.divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP);
            }
            Instant closedAt = sells.stream().map(Trade::ts).max(Comparator.naturalOrder()).orElse(now);
            LocalDate exitDate = closedAt.atZone(clock.zone()).toLocalDate();
            int held = clock.sessionsBetween(s.entryDate(), exitDate);
            if (store.close(s.id(), closedAt, exitDate, exit, held) && exit != null) {
                notifyGttExit(s, p, exit, held);
            }
        }
    }

    /** Plan M11.6: a close the position's GTT made is a stop or a goal hit, flagged when the session opened through the level. */
    private void notifyGttExit(SwingPosition s, Position p, BigDecimal exit, int held) {
        gtts.history(p.id()).stream().filter(g -> g.status() == money.hejje.execution.PositionGtt.Status.TRIGGERED && !g.updatedAt().isBefore(s.openedAt()))
                .reduce((a, b) -> b).ifPresent(g -> {
                    boolean stop = g.goal() == null || exit.compareTo(g.stop().add(g.goal()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)) < 0;
                    boolean gap = stop ? exit.compareTo(g.stop()) < 0 : exit.compareTo(g.goal()) > 0;
                    notify(stop ? "STOP_HIT" : "GOAL_HIT", s.id(), s.instrumentId(), Map.of("exit", exit.toPlainString(), "stop", g.stop().toPlainString(),
                            "goal", g.goal() == null ? "none" : g.goal().toPlainString(), "gapThrough", gap, "holdingDays", held, "gttId", g.brokerGttId()));
                });
    }

    private void notify(String event, UUID id, UUID instrumentId, Map<String, Object> extra) {
        Map<String, Object> data = new java.util.LinkedHashMap<>(extra);
        data.put("event", event);
        data.put("id", id.toString());
        data.put("instrumentId", instrumentId.toString());
        events.publishEvent(new money.hejje.common.ClientNotification("swing", data));
    }

    /** The position's delivery fills (same instrument, strategy and product), oldest first. */
    private List<Trade> fills(Position p, Instant now) {
        return orders.trades(p.mode(), p.instrumentId(), now.minus(LOOKBACK), now.plusSeconds(60)).stream()
                .filter(t -> t.isDelivery() && Objects.equals(t.strategyId(), p.strategyId())).toList();
    }

    /** The first buy after the last time the fills brought the position to zero: the entry of the open round trip. */
    private static Trade lastEntryFill(List<Trade> fills) {
        int net = 0;
        Trade entry = null;
        for (Trade t : fills) {
            if (net == 0 && t.side() == Side.BUY) {
                entry = t;
            }
            net += t.side() == Side.BUY ? t.quantity() : -t.quantity();
        }
        return net > 0 ? entry : null;
    }
}
