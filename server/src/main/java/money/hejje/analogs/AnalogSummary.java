package money.hejje.analogs;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The evidence for one symbol, session and lookback (docs/analogs.md). Every rate stands next to its count.
 *
 * @param checkpoint   {@code SESSION} only: the time of day (HH:mm) the session was compared at; empty for {@code DAILY}
 * @param candidates   windows that were eligible (lookback and longest forward window both ended before the benchmark)
 * @param compared     candidates that passed the scalar prefilter and had their path compared
 * @param matches      matches kept after the distance cap and de-overlapping
 * @param qualityTag   {@code STRONG | MODERATE | WEAK} from the median match quality; {@code NONE} without matches
 * @param narrative    five templated sentences: distribution, risk, reliability, match quality, takeaway
 * @param session      {@code SESSION} only, else null
 */
public record AnalogSummary(LocalDate sessionDate, UUID instrumentId, String symbol, AnalogKind kind, int lookback, String checkpoint,
        String engineVersion, int candidates, int compared, int matches, double medianQuality, String qualityTag, List<Outcome> outcomes,
        List<Split> splits, List<Context> context, List<String> narrative, SessionExtras session) {

    /**
     * What followed the matches over one forward window. Returns are percent. {@code avgPath}, {@code p25Path} and
     * {@code p75Path} are the cumulative return step by step. MAE/MFE are the worst and best cumulative return inside
     * the window per match; {@code maeP25} is the worse quartile.
     *
     * @param forward window label: sessions ahead ({@code DAILY}) or {@code "close"} ({@code SESSION})
     */
    public record Outcome(String forward, int count, double winRate, double mean, double median, double p25, double p75, double best, double worst,
            double maeMedian, double maeP25, double mfeMedian, double mfeP75, List<Double> avgPath, List<Double> p25Path, List<Double> p75Path,
            int distinctSymbols, int distinctYears, String direction, String consistency, String reliability, String risk, boolean outlier) {
    }

    /** The same matches split in two groups (same calendar month vs other months; same weekday vs other; expiry day vs not). */
    public record Split(String name, String forward, int count, double winRate, double median, int otherCount, double otherWinRate, double otherMedian) {
    }

    /** The benchmark's own scalar features for one lookback. */
    public record Context(int lookback, double volatility, double trend, double rangePosition, double volumeZ, double maxDrawdown) {
    }

    /**
     * Session analogs only: how the matched sessions treated the extremes that stood at the checkpoint, each with its
     * count, and when their highs and lows were made.
     *
     * @param highHeld       matches whose high at the checkpoint was still the session high at 15:10
     * @param lowHeld        ... and likewise the low
     * @param medianHighTime median time of day of the matches' session high (HH:mm)
     * @param medianReturnAtr median checkpoint-to-15:10 return in multiples of the daily ATR(14)
     */
    public record SessionExtras(int count, int highHeld, int lowHeld, String medianHighTime, String medianLowTime, double medianReturnAtr) {
    }
}
