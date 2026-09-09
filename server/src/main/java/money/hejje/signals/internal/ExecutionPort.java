package money.hejje.signals.internal;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Side;
import money.hejje.signals.StrategyPosition;

/**
 * How a runner reaches the market. The live port submits intents through the execution engine and learns about
 * fills from order events; the simulated port (parity harness, replay) fills immediately.
 */
public interface ExecutionPort {

    /** Places the protective stop for an open position; returns the stop order id, or empty when it was refused. */
    Optional<UUID> placeStop(StrategyPosition position, BigDecimal stop);

    /** Moves the stop; returns false when the modify was refused (the runner then re-places). */
    boolean modifyStop(StrategyPosition position, BigDecimal newStop);

    void cancelOrder(UUID orderId);

    /** Submits a market exit for the whole position; returns the exit order id, or empty when refused. */
    Optional<UUID> exitMarket(StrategyPosition position, String reason);

    /** True when the order is still working at the broker. */
    boolean isOrderLive(UUID orderId);

    /** True for the simulated port (parity harness, replay): fills happen immediately at the given reference price. */
    default boolean isSimulated() {
        return false;
    }

    /** Simulation hook: the fill price for an immediate fill at {@code reference}; live ports return empty. */
    default Optional<BigDecimal> simulatedFill(Side side, BigDecimal reference) {
        return Optional.empty();
    }
}
