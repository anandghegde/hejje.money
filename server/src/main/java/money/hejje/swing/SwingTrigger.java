package money.hejje.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import money.hejje.ratings.BaseType;

/**
 * The swing entry trigger (plan M11.4), pure. A READY trade plan (an M8.4 base or reversal not yet triggered) triggers
 * when a bar closes at or above its pivot and inside the buy zone (at most 5 % above the pivot), with a stop at least
 * {@code minStopDistancePct} below the price (Kite's GTT distance rule) and, for bases, a volume pace of at least
 * {@code volumePace}: the session's volume so far projected to a full session over the 50-session average. The entry
 * is a LIMIT at the trigger price plus {@code maxChaseBps}, never above the buy zone, on the tick grid.
 */
public final class SwingTrigger {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private SwingTrigger() {
    }

    /** A watched trade plan. {@code avgVolume50} is the 50-session average daily volume, null when unknown. */
    public record Setup(UUID baseId, UUID instrumentId, String symbol, BaseType type, BigDecimal pivot, BigDecimal buyHigh, BigDecimal stop, BigDecimal goal,
            Long avgVolume50) {}

    /** A deployment's trigger settings (its params over {@link SwingProperties}). */
    public record Params(BigDecimal volumePace, int maxChaseBps, BigDecimal minStopDistancePct, int sessionMinutes) {}

    public enum Verdict { WAIT, TRIGGER, ABOVE_BUY_ZONE, NO_VOLUME, STOP_TOO_NEAR }

    /** The verdict, the volume pace (null without a volume average) and, on TRIGGER, the entry LIMIT. */
    public record Result(Verdict verdict, BigDecimal pace, BigDecimal limit) {}

    /**
     * @param close          the bar's close
     * @param sessionVolume  the session's volume up to and including the bar
     * @param minutesElapsed minutes from the session open to the bar's close
     */
    public static Result evaluate(Setup s, BigDecimal close, long sessionVolume, int minutesElapsed, Params p, BigDecimal tick) {
        BigDecimal pace = pace(s.avgVolume50(), sessionVolume, minutesElapsed, p.sessionMinutes());
        if (close.compareTo(s.pivot()) < 0) {
            return new Result(Verdict.WAIT, pace, null);
        }
        if (close.compareTo(s.buyHigh()) > 0) {
            return new Result(Verdict.ABOVE_BUY_ZONE, pace, null);
        }
        BigDecimal distancePct = close.subtract(s.stop()).multiply(HUNDRED).divide(close, 4, RoundingMode.HALF_UP);
        if (distancePct.compareTo(p.minStopDistancePct()) < 0) {
            return new Result(Verdict.STOP_TOO_NEAR, pace, null);
        }
        if (!s.type().reversal() && (pace == null || pace.compareTo(p.volumePace()) < 0)) {
            return new Result(Verdict.NO_VOLUME, pace, null);
        }
        BigDecimal capped = close.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(p.maxChaseBps()).divide(TEN_THOUSAND, 8, RoundingMode.HALF_UP)));
        BigDecimal limit = floor(capped.min(s.buyHigh()), tick);
        return new Result(Verdict.TRIGGER, pace, limit);
    }

    /** Projected session volume over the 50-session average, two decimals; null without an average or elapsed time. */
    public static BigDecimal pace(Long avgVolume50, long sessionVolume, int minutesElapsed, int sessionMinutes) {
        if (avgVolume50 == null || avgVolume50 <= 0 || minutesElapsed <= 0) {
            return null;
        }
        BigDecimal projected = BigDecimal.valueOf(sessionVolume).multiply(BigDecimal.valueOf(sessionMinutes))
                .divide(BigDecimal.valueOf(Math.min(minutesElapsed, sessionMinutes)), 4, RoundingMode.HALF_UP);
        return projected.divide(BigDecimal.valueOf(avgVolume50), 2, RoundingMode.HALF_UP);
    }

    static BigDecimal floor(BigDecimal price, BigDecimal tick) {
        BigDecimal step = tick == null || tick.signum() <= 0 ? new BigDecimal("0.05") : tick;
        return price.divide(step, 0, RoundingMode.FLOOR).multiply(step).setScale(2, RoundingMode.HALF_UP);
    }
}
