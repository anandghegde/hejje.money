package money.hejje.signals.internal;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Ids;
import money.hejje.common.Side;
import money.hejje.signals.StrategyPosition;

/**
 * Fills immediately at the reference price (the backtester's BAR_CLOSE model): used by the parity harness and the
 * dev replay so the live runner's rule evaluation can be compared with the backtester without a broker.
 */
public final class SimulatedExecutionPort implements ExecutionPort {

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public Optional<BigDecimal> simulatedFill(Side side, BigDecimal reference) {
        return Optional.ofNullable(reference);
    }

    @Override
    public Optional<UUID> placeStop(StrategyPosition position, BigDecimal stop) {
        return Optional.of(Ids.newId());
    }

    @Override
    public boolean modifyStop(StrategyPosition position, BigDecimal newStop) {
        return true;
    }

    @Override
    public void cancelOrder(UUID orderId) {
    }

    @Override
    public Optional<UUID> exitMarket(StrategyPosition position, String reason) {
        return Optional.of(Ids.newId());
    }

    @Override
    public boolean isOrderLive(UUID orderId) {
        return true;
    }
}
