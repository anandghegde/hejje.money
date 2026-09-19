package money.hejje.signals.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import money.hejje.backtest.InstrumentMeta;
import money.hejje.backtest.Levels;
import money.hejje.common.Side;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.market.indicators.Bar;
import money.hejje.market.indicators.IndicatorContext;
import money.hejje.signals.CloseReason;
import money.hejje.signals.PositionStatus;
import money.hejje.signals.Signal;
import money.hejje.signals.StrategyPosition;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDefinition.Direction;
import money.hejje.strategy.StrategyDefinition.RuleMode;
import money.hejje.strategy.StrategyDefinition.RuleSet;
import money.hejje.strategy.StrategyDefinition.TargetType;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.dsl.ConditionEvaluator;
import money.hejje.strategy.dsl.EvalResult;
import money.hejje.strategy.dsl.EvalStatus;

/**
 * One deployment × instrument. Mirrors {@code InstrumentReplay} bar for bar (docs/signals.md, "Runner rules"): on a
 * closed candle it feeds the indicator context, manages an open position at the close, and evaluates the entry rules
 * when flat. Execution goes through an {@link ExecutionPort}; fills come back through the {@code on*} callbacks.
 * Not thread-safe: the engine serialises calls per runner.
 */
public final class StrategyRunner {

    /** What the runner asks the engine to persist or do. */
    public interface Callbacks {
        Signal signalCreated(StrategyRunner runner, Side side, Bar bar, double stop, BigDecimal target, Instant validUntil, List<EvalResult> evidence);

        void signalConsumed(Signal signal);

        StrategyPosition positionOpened(StrategyRunner runner, Signal signal, int qty, BigDecimal entry, BigDecimal stop, BigDecimal target, UUID entryOrderId);

        void positionUpdated(StrategyPosition position);

        void stopPlaced(StrategyPosition position, UUID stopOrderId);

        void stopMissing(StrategyPosition position, String detail);

        void exitTriggered(StrategyPosition position, CloseReason reason, UUID exitOrderId);

        void positionClosed(StrategyPosition position, CloseReason reason, BigDecimal exitPrice);
    }

    private final StrategyDeployment deployment;
    private final StrategyVersion version;
    private final StrategyDefinition def;
    private final InstrumentMeta meta;
    private final ZoneId zone;
    private final HejjeClock clock;
    private final ExecutionPort port;
    private final Callbacks callbacks;
    private final int defaultValidityMinutes;
    private volatile money.hejje.common.Money riskPerTrade;
    private final IndicatorContext ctx;
    private final Side side;

    private boolean paused;
    private LocalDate session;
    private int tradesToday;
    private Signal activeSignal;
    private StrategyPosition position;
    private CloseReason pendingClose;
    private boolean stopReplaced;
    private Bar lastBar;

    public StrategyRunner(StrategyDeployment deployment, StrategyVersion version, InstrumentMeta meta, HejjeClock clock, ExecutionPort port, Callbacks callbacks,
            int defaultValidityMinutes, money.hejje.common.Money riskPerTrade) {
        this.riskPerTrade = riskPerTrade;
        this.deployment = deployment;
        this.version = version;
        this.def = version.definition();
        this.meta = meta;
        this.clock = clock;
        this.zone = clock.zone();
        this.port = port;
        this.callbacks = callbacks;
        this.defaultValidityMinutes = defaultValidityMinutes;
        // a NEUTRAL (options) signal is stored as BUY with its stop at the lower band edge: the side only sizes the dry run,
        // the options position closes on either edge of the band (plan M6.4)
        this.side = def.direction() == Direction.SHORT ? Side.SELL : Side.BUY;
        this.ctx = new IndicatorContext(def.timeframe(), zone);
        ctx.registerDefinition(def);
        Levels.registerStopIndicators(ctx, def);
    }

    // --- identity and state ---

    public StrategyDeployment deployment() {
        return deployment;
    }

    public StrategyVersion version() {
        return version;
    }

    public InstrumentMeta meta() {
        return meta;
    }

    public UUID instrumentId() {
        return meta.id();
    }

    public Side side() {
        return side;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public synchronized Optional<StrategyPosition> position() {
        return Optional.ofNullable(position);
    }

    public Optional<Signal> activeSignal() {
        return Optional.ofNullable(activeSignal);
    }

    public IndicatorContext context() {
        return ctx;
    }

    public int tradesToday() {
        return tradesToday;
    }

    /** Records the entry order id on the pending (or already open) position without touching anything else. */
    public synchronized void setEntryOrderId(UUID positionId, UUID entryOrderId) {
        if (position != null && position.id().equals(positionId)) {
            StrategyPosition p = position;
            position = new StrategyPosition(p.id(), p.signalId(), p.deploymentId(), p.versionId(), p.strategyId(), p.instrumentId(), p.mode(), p.side(), p.quantity(),
                    p.entryPrice(), p.initialStop(), p.stop(), p.target(), entryOrderId, p.stopOrderId(), p.exitOrderId(), p.status(), p.closeReason(), p.exitPrice(),
                    p.openedAt(), p.closedAt(), p.updatedAt());
        }
    }

    /** Re-attaches a persisted position after a restart (the engine verifies/re-places the stop). */
    public synchronized void attach(StrategyPosition restored, int tradesToday) {
        this.position = restored;
        this.tradesToday = Math.max(this.tradesToday, tradesToday);
        this.session = restored.openedAt().atZone(zone).toLocalDate();
    }

    public synchronized void warmUp(List<Candle> candles) {
        for (Candle candle : candles) {
            if (candle.timeframe() == def.timeframe()) {
                Bar bar = Bar.of(candle, zone);
                if (!bar.session().equals(session)) {
                    session = bar.session();
                    tradesToday = 0;
                }
                ctx.onCandleClosed(candle);
                lastBar = bar;
            }
        }
    }

    // --- market events ---

    public synchronized void onCandleClosed(Candle candle) {
        if (candle.timeframe() != def.timeframe() || !candle.instrumentId().equals(meta.id())) {
            return;
        }
        if (lastBar != null && !candle.openTime().isAfter(lastBar.openTime())) {
            return; // duplicate or late candle
        }
        Bar bar = Bar.of(candle, zone);
        if (!bar.session().equals(session)) {
            session = bar.session();
            tradesToday = 0;
        }
        // an active signal from a previous bar is consumed by the new bar (validity = next bar close unless configured)
        if (activeSignal != null && !bar.closeTime().isBefore(activeSignal.validUntil())) {
            callbacks.signalConsumed(activeSignal);
            activeSignal = null;
        }
        if (position != null && position.status() == PositionStatus.OPEN) {
            checkBarExtremes(bar);
        }
        ctx.onCandleClosed(candle);
        lastBar = bar;
        if (position != null && position.status() == PositionStatus.OPEN) {
            manageAtClose(bar);
        }
        if (position == null && activeSignal == null && !paused) {
            evaluateEntry(bar);
        }
    }

    /** Tick-level target touch and software backup stop. */
    public synchronized void onTick(BigDecimal lastPrice) {
        if (position == null || position.status() != PositionStatus.OPEN || lastPrice == null) {
            return;
        }
        double price = lastPrice.doubleValue();
        if (position.target() != null && touchedTarget(price, price)) {
            exit(CloseReason.TARGET, lastPrice);
            return;
        }
        if (position.stopOrderId() == null && touchedStop(price, price)) {
            callbacks.stopMissing(position, "price crossed the stop with no live stop order");
            exit(CloseReason.SOFTWARE_STOP, lastPrice);
        }
    }

    private void checkBarExtremes(Bar bar) {
        boolean stopHit = touchedStop(bar.low(), bar.high());
        boolean targetHit = position.target() != null && touchedTarget(bar.low(), bar.high());
        if (stopHit && position.stopOrderId() == null) {
            callbacks.stopMissing(position, "bar crossed the stop with no live stop order");
            exit(CloseReason.SOFTWARE_STOP, BigDecimal.valueOf(side == Side.BUY ? Math.min(bar.open(), position.stop().doubleValue())
                    : Math.max(bar.open(), position.stop().doubleValue())));
        } else if (stopHit && port.isSimulated()) {
            // simulated broker: the resting stop fills at its level (or the open when gapped through), stop wins over target
            BigDecimal fill = BigDecimal.valueOf(side == Side.BUY ? Math.min(bar.open(), position.stop().doubleValue()) : Math.max(bar.open(), position.stop().doubleValue()));
            close(position.stop().compareTo(position.initialStop()) != 0 ? CloseReason.TRAILING_STOP : CloseReason.STOP, Levels.roundTarget(fill.doubleValue(), meta.tickSize()));
        } else if (targetHit && !stopHit) {
            exit(CloseReason.TARGET, position.target());
        }
    }

    private boolean touchedStop(double low, double high) {
        double stop = position.stop().doubleValue();
        return side == Side.BUY ? low <= stop : high >= stop;
    }

    private boolean touchedTarget(double low, double high) {
        double target = position.target().doubleValue();
        return side == Side.BUY ? high >= target : low <= target;
    }

    private void manageAtClose(Bar bar) {
        if (position.status() != PositionStatus.OPEN) {
            return;
        }
        if (def.trailingStop() != null) {
            Double trailed = Levels.trail(def, side, position.entryPrice(), position.entryPrice().subtract(position.initialStop()).abs(), bar.close(), ctx);
            if (trailed != null) {
                BigDecimal candidate = Levels.roundStop(trailed, side, meta.tickSize());
                boolean better = side == Side.BUY ? candidate.compareTo(position.stop()) > 0 : candidate.compareTo(position.stop()) < 0;
                if (better) {
                    moveStop(candidate);
                }
            }
        }
        if (def.target().type() == TargetType.VWAP) {
            OptionalDouble vwap = ctx.indicator("vwap", List.of(), 0);
            BigDecimal target = vwap.isPresent() ? Levels.roundTarget(vwap.getAsDouble(), meta.tickSize()) : null;
            position = withTarget(position, target);
            callbacks.positionUpdated(position);
        }
        if (position.status() != PositionStatus.OPEN) {
            return;
        }
        // exit rules apply from the first bar that did not start before the fill (BAR_CLOSE parity: the bar after the signal bar)
        if (def.exit() != null && !bar.openTime().isBefore(position.openedAt())) {
            List<EvalResult> results = ConditionEvaluator.evaluateAll(def.exit().conditions(), ctx);
            if (passes(def.exit(), results)) {
                exit(CloseReason.RULE_EXIT, BigDecimal.valueOf(bar.close()));
                return;
            }
        }
        if (def.maxHoldingMinutes() != null && Duration.between(position.openedAt(), bar.closeTime()).toMinutes() >= def.maxHoldingMinutes()) {
            exit(CloseReason.MAX_HOLDING, BigDecimal.valueOf(bar.close()));
            return;
        }
        if (!bar.closeTimeOfDay().isBefore(def.forceExitTime())) {
            exit(CloseReason.FORCE_EXIT, BigDecimal.valueOf(bar.close()));
        }
    }

    private void moveStop(BigDecimal candidate) {
        if (position.stopOrderId() != null) {
            if (!port.modifyStop(position, candidate)) {
                port.cancelOrder(position.stopOrderId());
                Optional<UUID> replaced = port.placeStop(position, candidate);
                position = withStop(position, candidate, replaced.orElse(null));
                callbacks.positionUpdated(position);
                replaced.ifPresent(id -> callbacks.stopPlaced(position, id));
                return;
            }
        }
        position = withStop(position, candidate, position.stopOrderId());
        callbacks.positionUpdated(position);
    }

    private void evaluateEntry(Bar bar) {
        LocalTime close = bar.closeTimeOfDay();
        if (close.isBefore(def.tradeWindow().start()) || close.isAfter(def.tradeWindow().end())) {
            return;
        }
        if (tradesToday >= def.maxTradesPerDay()) {
            return;
        }
        List<EvalResult> results = ConditionEvaluator.evaluateAll(def.entry().conditions(), ctx);
        if (!passes(def.entry(), results)) {
            return;
        }
        Double stop = Levels.stop(def, side, bar.close(), ctx);
        if (stop == null) {
            return;
        }
        BigDecimal roundedStop = Levels.roundStop(stop, side, meta.tickSize());
        BigDecimal reference = BigDecimal.valueOf(bar.close()).setScale(2, RoundingMode.HALF_UP);
        boolean stopValid = side == Side.BUY ? roundedStop.compareTo(reference) < 0 : roundedStop.compareTo(reference) > 0;
        if (!stopValid) {
            return;
        }
        BigDecimal riskPerUnit = reference.subtract(roundedStop).abs();
        BigDecimal target = Levels.target(def, side, reference, riskPerUnit, ctx, meta.tickSize());
        int validity = def.signalValidityMinutes() != null ? def.signalValidityMinutes() : defaultValidityMinutes;
        Instant validUntil = validity > 0 ? bar.closeTime().plus(Duration.ofMinutes(validity)) : bar.closeTime().plus(def.timeframe().duration());
        activeSignal = callbacks.signalCreated(this, side, bar, roundedStop.doubleValue(), target, validUntil, results);
        // simulated execution (parity / replay): fill at the signal bar's close
        // simulated fills round to the tick like the backtester's zero-slippage fill (buys up, sells down)
        Optional<BigDecimal> simulated = port.simulatedFill(side, Levels.roundToTick(reference, meta.tickSize(),
                side == Side.BUY ? RoundingMode.CEILING : RoundingMode.FLOOR));
        if (simulated.isPresent()) {
            Signal signal = activeSignal;
            activeSignal = null;
            int qty = sizeFor(signal, simulated.get());
            if (qty <= 0) {
                callbacks.signalConsumed(signal); // risk budget below one lot: the backtester skips this signal too
                return;
            }
            onSignalExecuted(signal, null);
            onEntryFilled(qty, simulated.get());
        }
    }

    /** Risk-based size for a simulated fill (the live path sizes in {@code SignalService.prepare} with the same inputs). */
    private int sizeFor(Signal signal, BigDecimal entry) {
        int maxQty = def.riskOverrides().maxQuantity() == null ? 0 : def.riskOverrides().maxQuantity();
        return money.hejje.risk.PositionSizer.size(money.hejje.common.Price.of(entry), money.hejje.common.Price.of(signal.stop()), riskPerTrade,
                meta.lotSize(), maxQty);
    }

    public money.hejje.common.Money riskPerTrade() {
        return riskPerTrade;
    }

    /** The deployment's risk per trade changed (size multiplier); applies to the next entry. */
    public void setRiskPerTrade(money.hejje.common.Money riskPerTrade) {
        this.riskPerTrade = riskPerTrade;
    }

    private static boolean passes(RuleSet rules, List<EvalResult> results) {
        if (results.isEmpty()) {
            return false;
        }
        return rules.mode() == RuleMode.ALL ? results.stream().allMatch(EvalResult::passed) : results.stream().anyMatch(EvalResult::passed);
    }

    // --- execution callbacks (from the engine) ---

    /** The signal was submitted as an order (entry pending). */
    public synchronized void onSignalExecuted(Signal signal, UUID entryOrderId) {
        if (activeSignal != null && activeSignal.id().equals(signal.id())) {
            activeSignal = null;
        }
        position = callbacks.positionOpened(this, signal, 0, null, signal.stop(), signal.target(), entryOrderId);
        tradesToday++;
    }

    public synchronized void onSignalGone(UUID signalId) {
        if (activeSignal != null && activeSignal.id().equals(signalId)) {
            activeSignal = null;
        }
    }

    /** The entry order filled completely: the position is open; place the protective stop. */
    public synchronized void onEntryFilled(int qty, BigDecimal averagePrice) {
        if (position == null || position.status() != PositionStatus.PENDING_ENTRY) {
            return;
        }
        BigDecimal riskPerUnit = averagePrice.subtract(position.initialStop()).abs();
        BigDecimal target = Levels.target(def, side, averagePrice, riskPerUnit, ctx, meta.tickSize());
        Instant openedAt = lastBar == null ? clock.now() : lastBar.closeTime();
        position = new StrategyPosition(position.id(), position.signalId(), position.deploymentId(), position.versionId(), position.strategyId(),
                position.instrumentId(), position.mode(), side, qty, averagePrice, position.initialStop(), position.stop(), target, position.entryOrderId(), null,
                null, PositionStatus.OPEN, null, null, openedAt, null, openedAt);
        callbacks.positionUpdated(position);
        Optional<UUID> stopId = port.placeStop(position, position.stop());
        if (stopId.isPresent()) {
            position = withStop(position, position.stop(), stopId.get());
            callbacks.positionUpdated(position);
            callbacks.stopPlaced(position, stopId.get());
        } else {
            callbacks.stopMissing(position, "protective stop was refused; software monitor active");
        }
    }

    /** The entry order died without a fill. */
    public synchronized void onEntryFailed(String detail) {
        if (position != null && position.status() == PositionStatus.PENDING_ENTRY) {
            StrategyPosition p = position;
            position = null;
            tradesToday = Math.max(0, tradesToday - 1);
            callbacks.positionClosed(p, CloseReason.ENTRY_FAILED, null);
        }
    }

    /** The protective stop filled. */
    public synchronized void onStopFilled(BigDecimal price) {
        if (position != null && position.status().isLive()) {
            close(position.stop().compareTo(position.initialStop()) != 0 ? CloseReason.TRAILING_STOP : CloseReason.STOP, price);
        }
    }

    /** The market exit filled. */
    public synchronized void onExitFilled(BigDecimal price) {
        if (position != null && position.status().isLive()) {
            close(pendingClose == null ? CloseReason.MANUAL : pendingClose, price);
        }
    }

    /** The stop order was cancelled or rejected by something other than the runner: re-place once, else exit. */
    public synchronized void onStopOrderDead(UUID orderId, String detail) {
        if (position == null || position.status() != PositionStatus.OPEN || !orderId.equals(position.stopOrderId())) {
            return;
        }
        callbacks.stopMissing(position, detail);
        position = withStop(position, position.stop(), null);
        callbacks.positionUpdated(position);
        if (!stopReplaced) {
            stopReplaced = true;
            Optional<UUID> replaced = port.placeStop(position, position.stop());
            if (replaced.isPresent()) {
                position = withStop(position, position.stop(), replaced.get());
                callbacks.positionUpdated(position);
                callbacks.stopPlaced(position, replaced.get());
                return;
            }
        }
        exit(CloseReason.SOFTWARE_STOP, position.stop());
    }

    /** Verifies the stop after a restart: re-places it when the broker no longer has it. */
    public synchronized void verifyStop() {
        if (position == null || position.status() != PositionStatus.OPEN) {
            return;
        }
        if (position.stopOrderId() != null && port.isOrderLive(position.stopOrderId())) {
            return;
        }
        callbacks.stopMissing(position, "stop order not live after restart");
        Optional<UUID> replaced = port.placeStop(position, position.stop());
        position = withStop(position, position.stop(), replaced.orElse(null));
        callbacks.positionUpdated(position);
        replaced.ifPresent(id -> callbacks.stopPlaced(position, id));
    }

    /** External request (deployment stopped, manual): flatten at market. */
    public synchronized void exitNow(CloseReason reason) {
        if (position != null && position.status() == PositionStatus.OPEN) {
            exit(reason, null);
        }
    }

    // --- exits ---

    private void exit(CloseReason reason, BigDecimal referencePrice) {
        if (position == null || position.status() != PositionStatus.OPEN) {
            return;
        }
        pendingClose = reason;
        Optional<BigDecimal> simulated = port.simulatedFill(side == Side.BUY ? Side.SELL : Side.BUY, referencePrice);
        if (simulated.isPresent()) {
            close(reason, simulated.get());
            return;
        }
        UUID stopOrderId = position.stopOrderId();
        position = new StrategyPosition(position.id(), position.signalId(), position.deploymentId(), position.versionId(), position.strategyId(),
                position.instrumentId(), position.mode(), position.side(), position.quantity(), position.entryPrice(), position.initialStop(), position.stop(),
                position.target(), position.entryOrderId(), stopOrderId, null, PositionStatus.EXITING, reason, null, position.openedAt(), null, clock.now());
        callbacks.positionUpdated(position);
        if (stopOrderId != null) {
            port.cancelOrder(stopOrderId);
        }
        Optional<UUID> exitId = port.exitMarket(position, reason.name());
        position = new StrategyPosition(position.id(), position.signalId(), position.deploymentId(), position.versionId(), position.strategyId(),
                position.instrumentId(), position.mode(), position.side(), position.quantity(), position.entryPrice(), position.initialStop(), position.stop(),
                position.target(), position.entryOrderId(), null, exitId.orElse(null), PositionStatus.EXITING, reason, null, position.openedAt(), null, clock.now());
        callbacks.positionUpdated(position);
        callbacks.exitTriggered(position, reason, exitId.orElse(null));
    }

    private void close(CloseReason reason, BigDecimal price) {
        StrategyPosition p = position;
        position = null;
        pendingClose = null;
        stopReplaced = false;
        callbacks.positionClosed(p, reason, price);
    }

    private StrategyPosition withStop(StrategyPosition p, BigDecimal stop, UUID stopOrderId) {
        return new StrategyPosition(p.id(), p.signalId(), p.deploymentId(), p.versionId(), p.strategyId(), p.instrumentId(), p.mode(), p.side(), p.quantity(),
                p.entryPrice(), p.initialStop(), stop, p.target(), p.entryOrderId(), stopOrderId, p.exitOrderId(), p.status(), p.closeReason(), p.exitPrice(),
                p.openedAt(), p.closedAt(), clock.now());
    }

    private StrategyPosition withTarget(StrategyPosition p, BigDecimal target) {
        return new StrategyPosition(p.id(), p.signalId(), p.deploymentId(), p.versionId(), p.strategyId(), p.instrumentId(), p.mode(), p.side(), p.quantity(),
                p.entryPrice(), p.initialStop(), p.stop(), target, p.entryOrderId(), p.stopOrderId(), p.exitOrderId(), p.status(), p.closeReason(), p.exitPrice(),
                p.openedAt(), p.closedAt(), clock.now());
    }

    /** Evidence as stored on the signal. */
    public static List<Map<String, Object>> evidenceOf(List<EvalResult> results) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvalResult e : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("condition", e.condition());
            item.put("status", e.status().name());
            if (e.status() != EvalStatus.NOT_READY) {
                item.put("lhs", e.observedLhs());
                item.put("rhs", e.observedRhs());
            }
            out.add(item);
        }
        return out;
    }
}
