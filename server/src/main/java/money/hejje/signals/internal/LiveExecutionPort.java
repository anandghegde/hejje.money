package money.hejje.signals.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ExecutionException;
import money.hejje.execution.ModifyCommand;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.signals.StrategyPosition;
import money.hejje.strategy.StrategyDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Reaches the broker through the execution engine (validation, risk, kill switch and idempotency all apply). */
@Component
public class LiveExecutionPort implements ExecutionPort {

    static final UUID SYSTEM_CLIENT = UUID.nameUUIDFromBytes("hejje:signals:system".getBytes(StandardCharsets.UTF_8));
    private static final Logger log = LoggerFactory.getLogger(LiveExecutionPort.class);

    private final ExecutionEngine engine;
    private final OrderService orders;
    private final java.util.function.Function<UUID, StrategyDefinition> definitions;

    LiveExecutionPort(ExecutionEngine engine, OrderService orders, money.hejje.strategy.StrategyService strategies) {
        this.engine = engine;
        this.orders = orders;
        this.definitions = versionId -> strategies.versionById(versionId).orElseThrow().definition();
    }

    @Override
    public Optional<UUID> placeStop(StrategyPosition p, BigDecimal stop) {
        Side exitSide = p.side() == Side.BUY ? Side.SELL : Side.BUY;
        String key = "stop:" + p.id() + ":" + stop.toPlainString() + ":" + (p.stopOrderId() == null ? "0" : p.stopOrderId());
        try {
            HejjeOrder order = engine.submit(new OrderIntentCommand(SYSTEM_CLIENT, key, ActorType.STRATEGY, "signals", p.strategyId(), p.signalId(),
                    p.instrumentId(), exitSide, Quantity.of(p.quantity()), OrderType.SL_M, definitions.apply(p.versionId()).product(), null, Price.of(stop),
                    null, null, null, OrderReason.STRATEGY_STOP));
            return order.state().isTerminal() && order.state() != OrderState.FILLED ? Optional.empty() : Optional.of(order.id());
        } catch (ExecutionException e) {
            log.warn("Stop order for position {} refused: {}", p.id(), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean modifyStop(StrategyPosition p, BigDecimal newStop) {
        try {
            HejjeOrder order = engine.modify(p.stopOrderId(), new ModifyCommand(null, null, null, Price.of(newStop)));
            return order.state().isLive() || order.state() == OrderState.OPEN;
        } catch (RuntimeException e) {
            log.warn("Modify of stop {} refused: {}", p.stopOrderId(), e.getMessage());
            return false;
        }
    }

    @Override
    public void cancelOrder(UUID orderId) {
        try {
            engine.cancel(orderId);
        } catch (RuntimeException e) {
            log.warn("Cancel of {} failed: {}", orderId, e.getMessage());
        }
    }

    @Override
    public Optional<UUID> exitMarket(StrategyPosition p, String reason) {
        Side exitSide = p.side() == Side.BUY ? Side.SELL : Side.BUY;
        String key = "exit:" + p.id() + ":" + reason;
        try {
            HejjeOrder order = engine.submit(new OrderIntentCommand(SYSTEM_CLIENT, key, ActorType.STRATEGY, "signals", p.strategyId(), p.signalId(),
                    p.instrumentId(), exitSide, Quantity.of(p.quantity()), OrderType.MARKET, definitions.apply(p.versionId()).product(), null, null, null, null,
                    null, OrderReason.STRATEGY_EXIT));
            return Optional.of(order.id());
        } catch (ExecutionException e) {
            log.error("Exit order for position {} refused: {}", p.id(), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean isOrderLive(UUID orderId) {
        return orders.findById(orderId).map(o -> o.state().isLive()).orElse(false);
    }
}
