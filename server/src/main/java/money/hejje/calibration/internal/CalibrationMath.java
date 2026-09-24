package money.hejje.calibration.internal;

import java.util.ArrayList;
import java.util.List;
import money.hejje.calibration.CalibrationReport.Bucket;
import money.hejje.calibration.CalibrationReport.TopVsBottom;

/** Buckets, Wilson intervals, Brier score and expected calibration error (plan M9.2). Pure. */
public final class CalibrationMath {

    public static final int BUCKETS = 10;
    private static final double Z = 1.959964;

    private CalibrationMath() {}

    /** One labelled prediction: its probability and whether the event happened. */
    public record Point(double p, boolean hit) {}

    public static int bucketOf(double p) {
        return Math.min(BUCKETS - 1, Math.max(0, (int) Math.floor(p * BUCKETS)));
    }

    /** Ten equal-width buckets; rate and interval only where the count reaches {@code minCount}. */
    public static List<Bucket> buckets(List<Point> points, int minCount) {
        int[] n = new int[BUCKETS];
        int[] hits = new int[BUCKETS];
        double[] sumP = new double[BUCKETS];
        for (Point x : points) {
            int b = bucketOf(x.p());
            n[b]++;
            sumP[b] += x.p();
            if (x.hit()) {
                hits[b]++;
            }
        }
        List<Bucket> out = new ArrayList<>();
        for (int b = 0; b < BUCKETS; b++) {
            double lo = round((double) b / BUCKETS);
            double hi = round((double) (b + 1) / BUCKETS);
            Double mean = n[b] == 0 ? null : round(sumP[b] / n[b]);
            if (n[b] >= minCount && n[b] > 0) {
                double[] w = wilson(hits[b], n[b]);
                out.add(new Bucket(lo, hi, n[b], hits[b], mean, round((double) hits[b] / n[b]), round(w[0]), round(w[1])));
            } else {
                out.add(new Bucket(lo, hi, n[b], hits[b], mean, null, null, null));
            }
        }
        return out;
    }

    /** Wilson score interval at 95 %. */
    public static double[] wilson(int hits, int n) {
        if (n == 0) {
            return new double[] {0, 1};
        }
        double p = (double) hits / n;
        double z2 = Z * Z;
        double centre = (p + z2 / (2 * n)) / (1 + z2 / n);
        double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n);
        return new double[] {Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    public static Double brier(List<Point> points) {
        if (points.isEmpty()) {
            return null;
        }
        double s = 0;
        for (Point x : points) {
            double y = x.hit() ? 1 : 0;
            s += (x.p() - y) * (x.p() - y);
        }
        return round(s / points.size());
    }

    /** Σ over buckets of (bucket share) × |mean probability − hit rate|, every non-empty bucket counted. */
    public static Double ece(List<Point> points) {
        if (points.isEmpty()) {
            return null;
        }
        int[] n = new int[BUCKETS];
        int[] hits = new int[BUCKETS];
        double[] sumP = new double[BUCKETS];
        for (Point x : points) {
            int b = bucketOf(x.p());
            n[b]++;
            sumP[b] += x.p();
            if (x.hit()) {
                hits[b]++;
            }
        }
        double e = 0;
        for (int b = 0; b < BUCKETS; b++) {
            if (n[b] > 0) {
                e += ((double) n[b] / points.size()) * Math.abs(sumP[b] / n[b] - (double) hits[b] / n[b]);
            }
        }
        return round(e);
    }

    /** The highest and lowest buckets with a rate; null when none has one. */
    public static TopVsBottom topVsBottom(List<Bucket> buckets) {
        Bucket bottom = null;
        Bucket top = null;
        for (Bucket b : buckets) {
            if (b.rate() != null) {
                if (bottom == null) {
                    bottom = b;
                }
                top = b;
            }
        }
        if (top == null) {
            return null;
        }
        return new TopVsBottom(top, bottom, top != bottom && top.wilsonLo() > bottom.wilsonHi());
    }

    static double round(double v) {
        return Math.round(v * 10_000d) / 10_000d;
    }
}
