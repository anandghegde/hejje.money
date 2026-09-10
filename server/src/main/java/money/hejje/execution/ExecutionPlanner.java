package money.hejje.execution;

import java.util.Objects;
import java.util.UUID;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import org.springframework.stereotype.Service;

/**
 * Position-aware (smart) intents (PRD 33, plan M5.3): the order that takes the current net position for an instrument,
 * product and strategy to a target. Callers and agents state the position they want; the planner works out the delta.
 */
@Service
public class ExecutionPlanner {

    private final OrderService orders;
    private final HejjeProperties properties;

    ExecutionPlanner(OrderService orders, HejjeProperties properties) {
        this.orders = orders;
        this.properties = properties;
    }

    /** @param side null and quantity 0 when the delta is zero (a no-op) */
    public record Plan(UUID instrumentId, Product product, UUID strategyId, int current, int target, int delta, Side side, int quantity) {
        public boolean noop() {
            return delta == 0;
        }
    }

    /** Pure: −50 → +100 is BUY 150 (PRD 33), +30 → −20 is SELL 50, equal is a no-op. */
    public static Plan plan(UUID instrumentId, Product product, UUID strategyId, int current, int target) {
        int delta = target - current;
        return new Plan(instrumentId, product, strategyId, current, target, delta, delta > 0 ? Side.BUY : delta < 0 ? Side.SELL : null, Math.abs(delta));
    }

    /** Against the current net position of (server mode, instrument, product, strategy; null strategy = manual book). */
    public Plan plan(UUID instrumentId, Product product, UUID strategyId, int target) {
        int current = orders.positions(properties.mode()).stream()
                .filter(p -> p.instrumentId().equals(instrumentId) && p.product() == product && Objects.equals(p.strategyId(), strategyId))
                .mapToInt(Position::netQuantity).sum();
        return plan(instrumentId, product, strategyId, current, target);
    }
}
