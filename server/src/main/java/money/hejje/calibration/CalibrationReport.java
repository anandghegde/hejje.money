package money.hejje.calibration;

import java.time.LocalDate;
import java.util.List;

/**
 * Hit rate per probability bucket (plan M9.2). Ten equal-width buckets; a bucket under {@code min-bucket-count} shows
 * its count and no rate. {@code n} counts labelled predictions (hits and misses), {@code none} those whose window
 * decided nothing, {@code pending} those not labelled yet. Brier and ECE are over the labelled predictions.
 */
public record CalibrationReport(String purpose, String version, LocalDate from, LocalDate to, int n, int none, int pending, int sessions,
        List<Bucket> buckets, Double brier, Double ece, TopVsBottom topVsBottom, boolean passes, List<String> reasons) {

    public CalibrationReport {
        buckets = List.copyOf(buckets);
        reasons = List.copyOf(reasons);
    }

    /** {@code rate} and the Wilson 95 % interval are null under the minimum count. */
    public record Bucket(double lo, double hi, int n, int hits, Double meanProbability, Double rate, Double wilsonLo, Double wilsonHi) {}

    /** The highest and lowest populated buckets; {@code separated} when the top's Wilson lower bound is above the bottom's upper bound. */
    public record TopVsBottom(Bucket top, Bucket bottom, boolean separated) {}
}
