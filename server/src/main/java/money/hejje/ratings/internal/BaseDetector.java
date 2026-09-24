package money.hejje.ratings.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.ratings.BaseType;
import money.hejje.ratings.RatingsProperties;

/**
 * Deterministic base detectors on a D1 series (docs/ratings.md, "Bases"). Each looks at session {@code i} and the
 * sessions before it only, and answers whether a pattern that has not broken out yet is complete as of {@code i}.
 * Every base needs a prior uptrend into its left high. Detectors are tried in the order cup with handle, cup, double
 * bottom, flat base; the first match wins.
 */
final class BaseDetector {

    /** The left high must be the highest high of this many sessions before it. */
    private static final int PEAK_SESSIONS = 10;

    /**
     * @param start index of the left high (a reversal: the session itself)
     * @param stop  the stop level when the pattern defines one (a reversal's low), else NaN
     */
    record Detected(BaseType type, int start, int end, double depthPct, double baseLow, double pivot, double stop, Map<String, Object> evidence) {
    }

    private final RatingsProperties.Bases cfg;

    BaseDetector(RatingsProperties.Bases cfg) {
        this.cfg = cfg;
    }

    /** The base patterns (not the reversal), first match in priority order. */
    Optional<Detected> base(DailySeries s, int i) {
        for (Optional<Detected> d : List.of(cup(s, i), doubleBottom(s, i), flatBase(s, i))) {
            if (d.isPresent()) {
                return d;
            }
        }
        return Optional.empty();
    }

    /** Flat base: at least {@code flat-min-sessions} below a left high, never deeper than {@code flat-max-depth-pct}; pivot = the left high. */
    Optional<Detected> flatBase(DailySeries s, int i) {
        int from = i - cfg.flatMaxSessions();
        if (from < 0) {
            return Optional.empty();
        }
        int start = lastArgMaxHigh(s, from, i);
        if (start == from || i - start < cfg.flatMinSessions() || !leftHigh(s, start)) {
            return Optional.empty();
        }
        int low = argMinLow(s, start + 1, i);
        double depth = depthPct(s.high()[start], s.low()[low]);
        if (depth > cfg.flatMaxDepthPct()) {
            return Optional.empty();
        }
        return Optional.of(new Detected(BaseType.FLAT_BASE, start, i, depth, s.low()[low], s.high()[start], Double.NaN,
                evidence(s, "leftHigh", start, "low", low, "sessions", i - start)));
    }

    /**
     * Cup: a left high, a low 12-35 % below it, a right side back to at least 90 % of the left high, 35-325 sessions from
     * high to high. With a handle (5-30 sessions after the right high, in the upper half of the cup, no deeper than 12 %)
     * the pivot is the handle's high (the right high); without one it is the left high.
     */
    Optional<Detected> cup(DailySeries s, int i) {
        int from = i - cfg.cupMaxSessions() - cfg.handleMaxSessions();
        if (from < 0) {
            from = 0;
        }
        int start = lastArgMaxHigh(s, from, i);
        if (start == from || !leftHigh(s, start) || i - start < cfg.cupMinSessions()) {
            return Optional.empty();
        }
        int low = argMinLow(s, start + 1, i);
        if (low >= i) {
            return Optional.empty();
        }
        double depth = depthPct(s.high()[start], s.low()[low]);
        if (depth < cfg.cupMinDepthPct() || depth > cfg.cupMaxDepthPct()) {
            return Optional.empty();
        }
        int right = lastArgMaxHigh(s, low + 1, i);
        int cupSessions = right - start;
        if (s.high()[right] < s.high()[start] * cfg.cupRecoveryPct() / 100.0 || cupSessions < cfg.cupMinSessions() || cupSessions > cfg.cupMaxSessions()) {
            return Optional.empty();
        }
        int handle = i - right;
        if (handle < cfg.handleMinSessions()) {
            return Optional.of(new Detected(BaseType.CUP, start, i, depth, s.low()[low], s.high()[start], Double.NaN,
                    evidence(s, "leftHigh", start, "low", low, "rightHigh", right, "sessions", i - start)));
        }
        if (handle > cfg.handleMaxSessions()) {
            return Optional.empty();
        }
        int handleLow = argMinLow(s, right + 1, i);
        double midpoint = s.low()[low] + (s.high()[start] - s.low()[low]) / 2.0;
        if ((cfg.handleUpperHalf() && s.low()[handleLow] < midpoint) || depthPct(s.high()[right], s.low()[handleLow]) > cfg.handleMaxDepthPct()) {
            return Optional.empty();
        }
        Map<String, Object> evidence = evidence(s, "leftHigh", start, "low", low, "rightHigh", right, "handleLow", handleLow, "sessions", i - start);
        evidence.put("handleSessions", handle);
        evidence.put("handleDepthPct", round2(depthPct(s.high()[right], s.low()[handleLow])));
        return Optional.of(new Detected(BaseType.CUP_WITH_HANDLE, start, i, depth, s.low()[low], s.high()[right], Double.NaN, evidence));
    }

    /**
     * Double bottom: two lows within {@code lows-within-pct} of each other, at least {@code min-separation} sessions
     * apart, with a middle peak between them; pivot = the middle peak. The second low may undercut the first or not
     * (config). Detected once the second low is at least three sessions old and no close has reached the pivot.
     */
    Optional<Detected> doubleBottom(DailySeries s, int i) {
        int from = Math.max(0, i - cfg.doubleBottomMaxSessions());
        int start = lastArgMaxHigh(s, from, i);
        if (start == from || !leftHigh(s, start) || i - start < cfg.doubleBottomMinSessions()) {
            return Optional.empty();
        }
        int lowest = argMinLow(s, start + 1, i);
        int separation = cfg.doubleBottomMinSeparation();
        // the lowest low as the second bottom (an undercut), else as the first
        if (lowest - separation > start + 1) {
            Optional<Detected> undercut = doubleBottom(s, start, argMinLow(s, start + 1, lowest - separation), lowest, i);
            if (undercut.isPresent()) {
                return undercut;
            }
        }
        if (!cfg.doubleBottomRequireUndercut() && lowest + separation <= i) {
            return doubleBottom(s, start, lowest, argMinLow(s, lowest + separation, i), i);
        }
        return Optional.empty();
    }

    private Optional<Detected> doubleBottom(DailySeries s, int start, int first, int second, int i) {
        double lowFirst = s.low()[first];
        double lowSecond = s.low()[second];
        if (Math.abs(lowSecond / lowFirst - 1.0) * 100.0 > cfg.doubleBottomLowsWithinPct() || i - second < 3) {
            return Optional.empty();
        }
        int peak = lastArgMaxHigh(s, first + 1, second - 1);
        double pivot = s.high()[peak];
        double baseLow = Math.min(lowFirst, lowSecond);
        double depth = depthPct(s.high()[start], baseLow);
        if (pivot < Math.max(lowFirst, lowSecond) * (1 + cfg.doubleBottomMinPeakPct() / 100.0) || pivot >= s.high()[start]
                || depth > cfg.doubleBottomMaxDepthPct()) {
            return Optional.empty();
        }
        for (int j = second + 1; j <= i; j++) {
            if (s.close()[j] >= pivot) {
                return Optional.empty(); // already through the pivot
            }
        }
        return Optional.of(new Detected(BaseType.DOUBLE_BOTTOM, start, i, depth, baseLow, pivot, Double.NaN,
                evidence(s, "leftHigh", start, "firstLow", first, "middlePeak", peak, "secondLow", second, "sessions", i - start)));
    }

    /**
     * Moving-average reversal: in an uptrend (close above a rising 50-DMA) the session trades down to the 20- or the
     * 50-DMA and closes above it in the upper part of its range. Pivot = the session's high, stop = its low.
     */
    Optional<Detected> reversal(DailySeries s, int i) {
        if (i < 50 + cfg.maRisingSessions() || !priorUptrend(s, i, s.close()[i])) {
            return Optional.empty();
        }
        double sma50 = sma(s, i, 50);
        double range = s.high()[i] - s.low()[i];
        if (s.close()[i] <= sma50 || sma50 <= sma(s, i - cfg.maRisingSessions(), 50) || range <= 0
                || (s.close()[i] - s.low()[i]) / range < cfg.reversalCloseInRange()) {
            return Optional.empty();
        }
        double sma20 = sma(s, i, 20);
        for (double[] ma : new double[][] {{20, sma20}, {50, sma50}}) {
            if (s.low()[i] <= ma[1] * (1 + cfg.maTouchPct() / 100.0) && s.close()[i] > ma[1]) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("movingAverage", (int) ma[0]);
                evidence.put("movingAverageValue", round2(ma[1]));
                evidence.put("closeInRange", round2((s.close()[i] - s.low()[i]) / range));
                return Optional.of(new Detected(BaseType.MA_REVERSAL, i, i, depthPct(s.high()[i], s.low()[i]), s.low()[i], s.high()[i], s.low()[i], evidence));
            }
        }
        return Optional.empty();
    }

    /** The left high is the highest high of the sessions just before it and ends an advance of at least {@code prior-uptrend-pct}. */
    private boolean leftHigh(DailySeries s, int start) {
        if (start < PEAK_SESSIONS) {
            return false;
        }
        for (int j = start - PEAK_SESSIONS; j < start; j++) {
            if (s.high()[j] > s.high()[start]) {
                return false;
            }
        }
        return priorUptrend(s, start, s.high()[start]);
    }

    private boolean priorUptrend(DailySeries s, int at, double level) {
        int from = Math.max(0, at - cfg.priorUptrendSessions());
        if (at - from < 20) {
            return false;
        }
        double low = s.low()[argMinLow(s, from, at - 1)];
        return low > 0 && (level / low - 1.0) * 100.0 >= cfg.priorUptrendPct();
    }

    private static int lastArgMaxHigh(DailySeries s, int from, int to) {
        int best = from;
        for (int j = from; j <= to; j++) {
            if (s.high()[j] >= s.high()[best]) {
                best = j;
            }
        }
        return best;
    }

    private static int argMinLow(DailySeries s, int from, int to) {
        int best = from;
        for (int j = from; j <= to; j++) {
            if (s.low()[j] < s.low()[best]) {
                best = j;
            }
        }
        return best;
    }

    static double sma(DailySeries s, int i, int period) {
        double sum = 0;
        for (int j = i - period + 1; j <= i; j++) {
            sum += s.close()[j];
        }
        return sum / period;
    }

    private static double depthPct(double high, double low) {
        return (high - low) / high * 100.0;
    }

    /** Pairs of (name, index) become dates; a name given an Integer count stays a number. */
    private static Map<String, Object> evidence(DailySeries s, Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int k = 0; k + 1 < pairs.length; k += 2) {
            String name = (String) pairs[k];
            int value = (Integer) pairs[k + 1];
            out.put(name, name.equals("sessions") ? (Object) value : s.date(value).toString());
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
