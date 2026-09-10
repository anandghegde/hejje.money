package money.hejje.backtest.experiments;

import java.util.List;

/** A variant's backtest in brief: overall and per split, walk-forward stability (std-dev of window expectancy, R) and quality warnings. */
public record VariantMetrics(SplitSummary overall, SplitSummary inSample, SplitSummary validation, SplitSummary outOfSample, Double walkForwardStdR, int windows,
        List<String> qualityWarnings, String resultHash) {

    public VariantMetrics {
        qualityWarnings = qualityWarnings == null ? List.of() : List.copyOf(qualityWarnings);
    }

    /** The slice rankings use: out-of-sample, else validation, else overall. */
    public SplitSummary basis() {
        return outOfSample != null ? outOfSample : validation != null ? validation : overall;
    }

    public String basisName() {
        return outOfSample != null ? "OUT_OF_SAMPLE" : validation != null ? "VALIDATION" : "OVERALL";
    }
}
