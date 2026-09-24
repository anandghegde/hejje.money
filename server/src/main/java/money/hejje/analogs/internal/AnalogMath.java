package money.hejje.analogs.internal;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import money.hejje.analogs.AnalogsProperties;

/** Window features, distance components, the similarity score and the 1-5 quality mapping (docs/analogs.md). */
final class AnalogMath {

    /** Knots of the component scores: a scaled distance (universe standard deviations) of 0.25 or less scores 5, 2.0 or more scores 1. */
    private static final double[] SCALED_X = {0.25, 0.5, 1.0, 1.5, 2.0};
    /** Knots of the shape score on the RMS distance between z-normalised paths (0 = identical, 1.41 = uncorrelated). */
    private static final double[] SHAPE_X = {0.30, 0.45, 0.65, 0.85, 1.10};
    private static final double[] SCORE_Y = {5, 4, 3, 2, 1};

    private AnalogMath() {
    }

    /**
     * Scalar features of a window.
     *
     * @param volatility    standard deviation of the log returns inside the window
     * @param trend         least-squares slope of the log closes times the window length (the fitted drift over the window)
     * @param rangePosition the last close within the window's close range, 0..1
     * @param volumeZ       the window's mean volume against the 50 sessions before it, in their standard deviations
     * @param maxDrawdown   the deepest close-to-close drawdown inside the window, as a positive fraction
     */
    record Scalars(double volatility, double trend, double rangePosition, double volumeZ, double maxDrawdown) {
    }

    /** Universe-wide standard deviations of the scalars over the eligible candidates; they scale the distances. */
    record Scales(double volatility, double trend, double rangePosition, double volumeZ, double maxDrawdown) {
    }

    /** Scalars of {@code values[end-length+1 .. end]} (log closes); {@code volumeZ} is supplied by the caller. */
    static Scalars scalars(double[] logClose, int end, int length, double volumeZ) {
        int start = end - length + 1;
        double sumReturn = 0;
        double sumReturnSq = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double peak = -Double.MAX_VALUE;
        double drawdown = 0;
        double sumT = 0;
        double sumY = 0;
        double sumTy = 0;
        double sumTt = 0;
        for (int i = start; i <= end; i++) {
            double y = logClose[i];
            if (i > start) {
                double r = y - logClose[i - 1];
                sumReturn += r;
                sumReturnSq += r * r;
            }
            min = Math.min(min, y);
            max = Math.max(max, y);
            peak = Math.max(peak, y);
            drawdown = Math.max(drawdown, 1.0 - Math.exp(y - peak));
            double t = i - start;
            sumT += t;
            sumY += y;
            sumTy += t * y;
            sumTt += t * t;
        }
        int returns = length - 1;
        double meanReturn = sumReturn / returns;
        double volatility = Math.sqrt(Math.max(0, sumReturnSq / returns - meanReturn * meanReturn));
        double slope = (length * sumTy - sumT * sumY) / (length * sumTt - sumT * sumT);
        return new Scalars(volatility, slope * length, max > min ? (logClose[end] - min) / (max - min) : 0.5, volumeZ, drawdown);
    }

    /** The window's mean volume against the {@code prior} sessions before it, in their standard deviations (0 when those are constant). */
    static double volumeZ(double[] volume, int end, int length, int prior) {
        int start = end - length + 1;
        double sum = 0;
        double sumSq = 0;
        for (int i = start - prior; i < start; i++) {
            sum += volume[i];
            sumSq += volume[i] * volume[i];
        }
        double mean = sum / prior;
        double sd = Math.sqrt(Math.max(0, sumSq / prior - mean * mean));
        double window = 0;
        for (int i = start; i <= end; i++) {
            window += volume[i];
        }
        return sd <= 0 ? 0 : Math.max(-5, Math.min(5, (window / length - mean) / sd));
    }

    /** {@code values[end-length+1 .. end]} z-normalised; null for a constant window. */
    static double[] zPath(double[] values, int end, int length) {
        int start = end - length + 1;
        double sum = 0;
        double sumSq = 0;
        for (int i = start; i <= end; i++) {
            sum += values[i];
            sumSq += values[i] * values[i];
        }
        double mean = sum / length;
        double sd = Math.sqrt(Math.max(0, sumSq / length - mean * mean));
        if (sd <= 1e-12) {
            return null;
        }
        double[] z = new double[length];
        for (int i = 0; i < length; i++) {
            z[i] = (values[start + i] - mean) / sd;
        }
        return z;
    }

    /** Pearson correlation of a z-normalised benchmark path with {@code values[end-length+1 .. end]}; NaN for a constant window. */
    static double correlation(double[] z, double[] values, int end) {
        int length = z.length;
        int start = end - length + 1;
        double sum = 0;
        double sumSq = 0;
        double dot = 0;
        for (int i = 0; i < length; i++) {
            double x = values[start + i];
            sum += x;
            sumSq += x * x;
            dot += z[i] * x;
        }
        double mean = sum / length;
        double sd = Math.sqrt(Math.max(0, sumSq / length - mean * mean));
        return sd <= 1e-12 ? Double.NaN : Math.max(-1, Math.min(1, dot / (length * sd)));
    }

    /** As above for a float series, over its first {@code z.length} values. */
    static double correlation(double[] z, float[] values, int end) {
        int length = z.length;
        int start = end - length + 1;
        double sum = 0;
        double sumSq = 0;
        double dot = 0;
        for (int i = 0; i < length; i++) {
            double x = values[start + i];
            sum += x;
            sumSq += x * x;
            dot += z[i] * x;
        }
        double mean = sum / length;
        double sd = Math.sqrt(Math.max(0, sumSq / length - mean * mean));
        return sd <= 1e-12 ? Double.NaN : Math.max(-1, Math.min(1, dot / (length * sd)));
    }

    /** The seven raw distance components, in Konseki's names. {@code shape_distance} is the RMS distance of the z-paths, sqrt(2 (1 - r)). */
    static Map<String, Double> components(double correlation, Scalars benchmark, Scalars candidate) {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("path_correlation", round4(correlation));
        c.put("shape_distance", round4(Math.sqrt(Math.max(0, 2 * (1 - correlation)))));
        c.put("volatility_distance", round6(Math.abs(benchmark.volatility() - candidate.volatility())));
        c.put("trend_distance", round6(Math.abs(benchmark.trend() - candidate.trend())));
        c.put("range_position_distance", round4(Math.abs(benchmark.rangePosition() - candidate.rangePosition())));
        c.put("volume_distance", round4(Math.abs(benchmark.volumeZ() - candidate.volumeZ())));
        c.put("risk_distance", round6(Math.abs(benchmark.maxDrawdown() - candidate.maxDrawdown())));
        return c;
    }

    /** Weighted sum of the components, each scalar distance divided by its universe standard deviation; lower is closer. */
    static double similarity(double correlation, Scalars b, Scalars c, Scales s, AnalogsProperties.Weights w) {
        return w.shape() * Math.sqrt(Math.max(0, 2 * (1 - correlation))) + w.correlation() * (1 - correlation)
                + w.volatility() * Math.abs(b.volatility() - c.volatility()) / s.volatility()
                + w.trend() * Math.abs(b.trend() - c.trend()) / s.trend()
                + w.rangePosition() * Math.abs(b.rangePosition() - c.rangePosition()) / s.rangePosition()
                + w.volume() * Math.abs(b.volumeZ() - c.volumeZ()) / s.volumeZ()
                + w.risk() * Math.abs(b.maxDrawdown() - c.maxDrawdown()) / s.maxDrawdown();
    }

    /** Six 1-5 component scores: shape from the RMS path distance, the others from their scaled distance. */
    static Map<String, Double> scores(double correlation, Scalars b, Scalars c, Scales s) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("shape", round2(piecewise(SHAPE_X, Math.sqrt(Math.max(0, 2 * (1 - correlation))))));
        out.put("trend", round2(piecewise(SCALED_X, Math.abs(b.trend() - c.trend()) / s.trend())));
        out.put("volatility", round2(piecewise(SCALED_X, Math.abs(b.volatility() - c.volatility()) / s.volatility())));
        out.put("rangePosition", round2(piecewise(SCALED_X, Math.abs(b.rangePosition() - c.rangePosition()) / s.rangePosition())));
        out.put("volume", round2(piecewise(SCALED_X, Math.abs(b.volumeZ() - c.volumeZ()) / s.volumeZ())));
        out.put("risk", round2(piecewise(SCALED_X, Math.abs(b.maxDrawdown() - c.maxDrawdown()) / s.maxDrawdown())));
        return out;
    }

    /** Monotone piecewise-linear map onto 5..1, clamped outside the knots (the scoring module's PiecewiseLinear, copied not shared). */
    static double piecewise(double[] xs, double x) {
        if (x <= xs[0]) {
            return SCORE_Y[0];
        }
        for (int i = 1; i < xs.length; i++) {
            if (x <= xs[i]) {
                double t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
                return SCORE_Y[i - 1] + t * (SCORE_Y[i] - SCORE_Y[i - 1]);
            }
        }
        return SCORE_Y[SCORE_Y.length - 1];
    }

    /** Standard deviation with a floor so a degenerate universe never divides by zero. */
    static double sd(double sum, double sumSq, long n, double floor) {
        if (n == 0) {
            return floor;
        }
        double mean = sum / n;
        return Math.max(floor, Math.sqrt(Math.max(0, sumSq / n - mean * mean)));
    }

    /** Linear-interpolation percentile (p in 0..1) of a sorted array. */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double position = p * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = Math.min(sorted.length - 1, lower + 1);
        return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return percentile(sorted, 0.5);
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }

    static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
