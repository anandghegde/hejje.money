package money.hejje.swing;

import java.math.BigDecimal;

/**
 * The swing trailing rule (plan M11.2): once the close is at least 1R above the entry the stop moves to breakeven, and
 * from breakeven on it trails one tick under the 20-day low. Stops only tighten: the result is never below the current
 * stop.
 */
public final class SwingTrail {

    private SwingTrail() {
    }

    /**
     * @param entry       the entry price
     * @param initialStop the stop at entry (entry − initial stop is 1R)
     * @param currentStop the stop in force
     * @param close       the last session's close
     * @param low20       the lowest low of the last 20 sessions, or null when unknown
     * @param tick        the instrument's tick size
     */
    public static BigDecimal next(BigDecimal entry, BigDecimal initialStop, BigDecimal currentStop, BigDecimal close, BigDecimal low20, BigDecimal tick) {
        BigDecimal r = entry.subtract(initialStop);
        if (r.signum() <= 0) {
            return currentStop;
        }
        BigDecimal candidate = currentStop;
        if (close.compareTo(entry.add(r)) >= 0) {
            candidate = candidate.max(entry);
        }
        if (candidate.compareTo(entry) >= 0 && low20 != null) {
            candidate = candidate.max(low20.subtract(tick));
        }
        return candidate.max(currentStop);
    }
}
