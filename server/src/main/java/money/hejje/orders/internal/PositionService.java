package money.hejje.orders.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.orders.Position;
import money.hejje.orders.PositionChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Applies fills to the average-cost position book and computes realized P&L. */
@Service
public class PositionService {

    private final PositionStore store;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;

    PositionService(PositionStore store, HejjeClock clock, ApplicationEventPublisher events) {
        this.store = store;
        this.clock = clock;
        this.events = events;
    }

    /** Applies a fill and returns the updated position. Realized P&L accrues on the reducing part of the fill. */
    public Position applyFill(ExecutionMode mode, UUID instrumentId, Product product, UUID strategyId, Side side, int qty, BigDecimal price) {
        Instant now = clock.now();
        Position current = store.find(mode, instrumentId, product, strategyId).orElse(null);
        int net = current == null ? 0 : current.netQuantity();
        BigDecimal avg = current == null ? BigDecimal.ZERO.setScale(2) : current.averagePrice();
        Money realized = current == null ? Money.ZERO : current.realizedPnl();
        int dayBuy = current == null ? 0 : current.dayBuyQty();
        int daySell = current == null ? 0 : current.daySellQty();
        UUID id = current == null ? Ids.newId() : current.id();
        Instant openedAt = current == null ? now : current.openedAt();

        int signed = side == Side.BUY ? qty : -qty;
        if (side == Side.BUY) {
            dayBuy += qty;
        } else {
            daySell += qty;
        }

        if (net == 0 || Integer.signum(net) == Integer.signum(signed)) {
            BigDecimal total = avg.multiply(BigDecimal.valueOf(Math.abs(net))).add(price.multiply(BigDecimal.valueOf(qty)));
            net += signed;
            avg = total.divide(BigDecimal.valueOf(Math.abs(net)), 2, RoundingMode.HALF_UP);
        } else {
            int closing = Math.min(Math.abs(net), qty);
            BigDecimal perUnit = net > 0 ? price.subtract(avg) : avg.subtract(price);
            realized = realized.plus(Money.of(perUnit.multiply(BigDecimal.valueOf(closing)).setScale(2, RoundingMode.HALF_UP)));
            int remaining = qty - closing;
            net += signed;
            if (net == 0) {
                avg = BigDecimal.ZERO.setScale(2);
            } else if (remaining > 0) {
                avg = price; // position flipped; new leg opens at the fill price
            }
        }

        Position updated = new Position(id, mode, instrumentId, product, strategyId, net, avg, realized, dayBuy, daySell, openedAt, now);
        store.upsert(updated);
        events.publishEvent(new PositionChangedEvent(EventMeta.create(clock), id, instrumentId, net));
        return updated;
    }

    public Position find(ExecutionMode mode, UUID instrumentId, Product product, UUID strategyId) {
        return store.find(mode, instrumentId, product, strategyId).orElse(null);
    }
}
