package money.hejje.analogs;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Analog engine settings ({@code hejje.analogs.*}, config/analogs.yaml, docs/analogs.md).
 *
 * @param enabled            when false nothing is computed and the API answers 503
 * @param universe           universe file of the daily engine
 * @param engineVersion      stored with every row; bump when a rule, weight or threshold changes (old rows are kept)
 * @param lookbacks          window lengths in sessions
 * @param forwards           forward windows in sessions; the longest must have passed before the benchmark date for a window to be a candidate
 * @param maxMatches         matches kept per symbol and lookback after de-overlapping
 * @param maxDistance        a candidate with a larger similarity score is no match
 * @param minEvidence        below this many matches the direction is {@code INSUFFICIENT}
 * @param narrativeForward   forward window the templated read talks about
 * @param parallelism        worker threads of a run
 * @param nightlyBudget      a nightly run that takes longer is logged as over budget
 * @param matchRetentionDays match rows older than this are deleted (summaries are kept forever)
 */
@ConfigurationProperties("hejje.analogs")
public record AnalogsProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("nifty500") String universe,
        @DefaultValue("1") String engineVersion,
        @DefaultValue({"5", "10", "15", "20", "25", "30", "40", "50"}) List<Integer> lookbacks,
        @DefaultValue({"3", "5", "10", "15"}) List<Integer> forwards,
        @DefaultValue("50") int maxMatches,
        @DefaultValue("1.5") double maxDistance,
        @DefaultValue("10") int minEvidence,
        @DefaultValue("5") int narrativeForward,
        @DefaultValue("4") int parallelism,
        @DefaultValue("PT30M") Duration nightlyBudget,
        @DefaultValue("30") int matchRetentionDays,
        @DefaultValue Weights weights,
        @DefaultValue Prefilter prefilter,
        @DefaultValue Tags tags,
        @DefaultValue Session session) {

    /** Weights of the distance components in the similarity score (shape-heavy); they need not sum to 1. */
    public record Weights(@DefaultValue("0.30") double shape, @DefaultValue("0.20") double correlation, @DefaultValue("0.12") double volatility,
            @DefaultValue("0.12") double trend, @DefaultValue("0.10") double rangePosition, @DefaultValue("0.08") double volume,
            @DefaultValue("0.08") double risk) {
    }

    /** A candidate passes when each scalar is within this many universe standard deviations of the benchmark's. */
    public record Prefilter(@DefaultValue("1.0") double volatility, @DefaultValue("1.0") double trend, @DefaultValue("1.0") double rangePosition) {
    }

    /**
     * Tag thresholds. Percent thresholds scale with the square root of the forward window (a 10-session move is compared
     * with {@code x * sqrt(10)}).
     *
     * @param strongWinRate   win rate for {@code BULLISH_STRONG} (mirror: {@code 1 - x} for bearish), with the median on the same side
     * @param leanWinRate     win rate for {@code BULLISH}
     * @param tightIqrPct     IQR at or below this (x sqrt f) is {@code TIGHT}
     * @param wideIqrPct      IQR at or above this (x sqrt f) is {@code WIDE}
     * @param highRiskMaePct  median MAE at or below minus this (x sqrt f) is {@code HIGH} risk
     * @param moderateRiskMaePct ... {@code MODERATE}
     * @param outlierShare    the mean moves by more than this share of itself (and by at least 0.1 %) without the two most extreme matches
     */
    public record Tags(@DefaultValue("0.65") double strongWinRate, @DefaultValue("0.55") double leanWinRate, @DefaultValue("0.8") double tightIqrPct,
            @DefaultValue("1.6") double wideIqrPct, @DefaultValue("1.0") double highRiskMaePct, @DefaultValue("0.5") double moderateRiskMaePct,
            @DefaultValue("0.3") double outlierShare, @DefaultValue("30") int highReliabilityCount, @DefaultValue("15") int highReliabilitySymbols,
            @DefaultValue("4") int highReliabilityYears, @DefaultValue("15") int mediumReliabilityCount,
            @DefaultValue("8") int mediumReliabilitySymbols, @DefaultValue("2") int mediumReliabilityYears) {
    }

    /**
     * Session analogs (plan M8.6): today's session so far against every past session of the intraday universe.
     *
     * @param universe     universe file whose constituents have M5 history
     * @param extraSymbols further instruments (the indices); continuous futures series are always included
     * @param checkpoints  times of day (IST, HH:mm) at which the session is compared
     * @param exitTime     the forward window ends here (the strategies' force-exit time)
     * @param contextWeight weight of the prior-session context distance, on top of {@link Weights}
     * @param percentScale multiplier of the percent tag thresholds for the checkpoint-to-exit window
     * @param volumeSessions sessions of the same-time cumulative volume average
     */
    public record Session(@DefaultValue("nifty50") String universe, @DefaultValue({"INDEX:NIFTY 50", "INDEX:NIFTY BANK"}) List<String> extraSymbols,
            @DefaultValue({"09:45", "10:15", "11:15", "13:00"}) List<String> checkpoints, @DefaultValue("15:10") String exitTime,
            @DefaultValue("0.10") double contextWeight, @DefaultValue("1.0") double percentScale, @DefaultValue("20") int volumeSessions) {
    }
}
