package money.hejje.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalDouble;
import money.hejje.common.Price;
import money.hejje.common.Side;

/**
 * Pure stop suggestion: {@value #ATR_MULTIPLE} x ATR({@value #ATR_PERIOD}) from the entry, or {@value #FALLBACK_PCT}%
 * without bars, never further than the {@code maxStopDistancePct} limit, rounded to the tick towards the entry so the
 * risk engine's {@code maxStopDistance} check always passes, and never equal to the entry.
 */
public final class StopSuggester {

    public static final int ATR_PERIOD = 14;
    public static final BigDecimal ATR_MULTIPLE = new BigDecimal("1.5");
    public static final BigDecimal FALLBACK_PCT = new BigDecimal("1.0");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private StopSuggester() {
    }

    public static StopSuggestion suggest(Side side, Price entry, OptionalDouble atr, BigDecimal maxDistancePct, BigDecimal tick) {
        if (entry.value().signum() <= 0) {
            throw new IllegalArgumentException("Entry must be positive: " + entry.asText());
        }
        boolean longSide = side == Side.BUY;
        BigDecimal e = entry.value();
        BigDecimal distance;
        String basis;
        BigDecimal atrValue = null;
        if (atr.isPresent() && Double.isFinite(atr.getAsDouble()) && atr.getAsDouble() > 0) {
            atrValue = BigDecimal.valueOf(atr.getAsDouble()).setScale(4, RoundingMode.HALF_UP);
            distance = atrValue.multiply(ATR_MULTIPLE);
            basis = "ATR";
        } else {
            distance = e.multiply(FALLBACK_PCT).divide(HUNDRED, 8, RoundingMode.HALF_UP);
            basis = "PERCENT";
        }
        BigDecimal maxDistance = e.multiply(maxDistancePct).divide(HUNDRED, 8, RoundingMode.HALF_UP);
        if (distance.compareTo(maxDistance) > 0) {
            distance = maxDistance;
            basis = "MAX_DISTANCE";
        }
        BigDecimal raw = longSide ? e.subtract(distance) : e.add(distance);
        // round towards the entry so the stop stays inside the limit
        BigDecimal ticks = raw.divide(tick, 0, longSide ? RoundingMode.CEILING : RoundingMode.FLOOR);
        BigDecimal rounded = ticks.multiply(tick).setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(e) == 0) {
            rounded = longSide ? e.subtract(tick) : e.add(tick);
        }
        Price stop = Price.of(rounded);
        BigDecimal distancePctOut = e.subtract(stop.value()).abs().multiply(HUNDRED).divide(e, 2, RoundingMode.HALF_UP);
        return new StopSuggestion(entry, stop, basis, atrValue, distancePctOut, maxDistancePct);
    }
}
