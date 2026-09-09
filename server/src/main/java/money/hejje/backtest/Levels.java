package money.hejje.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.OptionalDouble;
import money.hejje.common.Side;
import money.hejje.market.indicators.IndicatorContext;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDefinition.StopType;
import money.hejje.strategy.dsl.Expr.Arg;

/**
 * Stop, target and trailing-stop levels from a definition and the indicator context at the signal bar. Shared with
 * the live signal engine (M2.6) so backtest and live levels agree to the tick.
 */
public final class Levels {

    public static final int ATR_PERIOD = 14;
    public static final int SWING_LOOKBACK = 10;
    private static final List<Arg> ATR_ARGS = List.of(new Arg.Number(ATR_PERIOD));
    private static final List<Arg> SWING_ARGS = List.of(new Arg.Number(SWING_LOOKBACK));

    private Levels() {
    }

    /** Registers the indicators the stop/target/trailing types need so they warm up with the rest. */
    public static void registerStopIndicators(IndicatorContext ctx, StrategyDefinition def) {
        switch (def.stop().type()) {
            case OPENING_RANGE_LOW -> ctx.register("opening_range_low", List.of());
            case OPENING_RANGE_HIGH -> ctx.register("opening_range_high", List.of());
            case ATR_MULTIPLE -> ctx.register("atr", ATR_ARGS);
            case SWING_LOW -> ctx.register("lowest", SWING_ARGS);
            case SWING_HIGH -> ctx.register("highest", SWING_ARGS);
            case PREV_DAY_LOW -> ctx.register("prev_day_low", List.of());
            case PREV_DAY_HIGH -> ctx.register("prev_day_high", List.of());
            default -> {
            }
        }
        if (def.trailingStop() != null && def.trailingStop().type() == StrategyDefinition.TrailingType.ATR_MULTIPLE) {
            ctx.register("atr", ATR_ARGS);
        }
        if (def.target().type() == StrategyDefinition.TargetType.VWAP) {
            ctx.register("vwap", List.of());
        }
    }

    /** Raw stop level for a signal at {@code reference} (the signal bar's close), or null when not computable. */
    public static Double stop(StrategyDefinition def, Side side, double reference, IndicatorContext ctx) {
        StopType type = def.stop().type();
        double value = def.stop().value() == null ? 0 : def.stop().value().doubleValue();
        boolean longSide = side == Side.BUY;
        OptionalDouble level = switch (type) {
            case OPENING_RANGE_LOW -> ctx.indicator("opening_range_low", List.of(), 0);
            case OPENING_RANGE_HIGH -> ctx.indicator("opening_range_high", List.of(), 0);
            case ATR_MULTIPLE -> {
                OptionalDouble atr = ctx.indicator("atr", ATR_ARGS, 0);
                yield atr.isEmpty() ? atr : OptionalDouble.of(longSide ? reference - value * atr.getAsDouble() : reference + value * atr.getAsDouble());
            }
            case PERCENT -> OptionalDouble.of(longSide ? reference * (1 - value / 100.0) : reference * (1 + value / 100.0));
            case POINTS -> OptionalDouble.of(longSide ? reference - value : reference + value);
            case SWING_LOW -> ctx.indicator("lowest", SWING_ARGS, 0);
            case SWING_HIGH -> ctx.indicator("highest", SWING_ARGS, 0);
            case PREV_DAY_LOW -> ctx.indicator("prev_day_low", List.of(), 0);
            case PREV_DAY_HIGH -> ctx.indicator("prev_day_high", List.of(), 0);
        };
        if (level.isEmpty() || !Double.isFinite(level.getAsDouble()) || level.getAsDouble() <= 0) {
            return null;
        }
        return level.getAsDouble();
    }

    /** Target price from the fill and the per-unit risk, or null for {@code none}/not computable. */
    public static BigDecimal target(StrategyDefinition def, Side side, BigDecimal entry, BigDecimal riskPerUnit, IndicatorContext ctx, BigDecimal tick) {
        boolean longSide = side == Side.BUY;
        BigDecimal value = def.target().value();
        BigDecimal raw = switch (def.target().type()) {
            case NONE -> null;
            case RISK_MULTIPLE -> longSide ? entry.add(riskPerUnit.multiply(value)) : entry.subtract(riskPerUnit.multiply(value));
            case POINTS -> longSide ? entry.add(value) : entry.subtract(value);
            case PERCENT -> {
                BigDecimal delta = entry.multiply(value).movePointLeft(2);
                yield longSide ? entry.add(delta) : entry.subtract(delta);
            }
            case VWAP -> {
                OptionalDouble vwap = ctx.indicator("vwap", List.of(), 0);
                yield vwap.isEmpty() ? null : BigDecimal.valueOf(vwap.getAsDouble());
            }
        };
        if (raw == null || raw.signum() <= 0) {
            return null;
        }
        return roundTarget(raw.doubleValue(), tick);
    }

    /** Candidate trailing stop at a bar close, or null when not computable. */
    public static Double trail(StrategyDefinition def, Side side, BigDecimal entry, BigDecimal riskPerUnit, double close, IndicatorContext ctx) {
        double value = def.trailingStop().value().doubleValue();
        boolean longSide = side == Side.BUY;
        return switch (def.trailingStop().type()) {
            case ATR_MULTIPLE -> {
                OptionalDouble atr = ctx.indicator("atr", ATR_ARGS, 0);
                yield atr.isEmpty() ? null : (longSide ? close - value * atr.getAsDouble() : close + value * atr.getAsDouble());
            }
            case PERCENT -> longSide ? close * (1 - value / 100.0) : close * (1 + value / 100.0);
            case BREAKEVEN_AT_R -> {
                double trigger = riskPerUnit.doubleValue() * value;
                boolean reached = longSide ? close >= entry.doubleValue() + trigger : close <= entry.doubleValue() - trigger;
                yield reached ? entry.doubleValue() : null;
            }
        };
    }

    /** Stops round away from the entry (long stops down, short stops up) so the protection is never tighter than asked. */
    public static BigDecimal roundStop(double raw, Side side, BigDecimal tick) {
        return roundToTick(BigDecimal.valueOf(raw), tick, side == Side.BUY ? RoundingMode.FLOOR : RoundingMode.CEILING);
    }

    public static BigDecimal roundTarget(double raw, BigDecimal tick) {
        return roundToTick(BigDecimal.valueOf(raw), tick, RoundingMode.HALF_UP);
    }

    public static BigDecimal roundToTick(BigDecimal price, BigDecimal tick, RoundingMode mode) {
        BigDecimal ticks = price.divide(tick, 0, mode);
        BigDecimal rounded = ticks.multiply(tick).setScale(2, RoundingMode.HALF_UP);
        return rounded.signum() > 0 ? rounded : tick.setScale(2, RoundingMode.HALF_UP);
    }
}
