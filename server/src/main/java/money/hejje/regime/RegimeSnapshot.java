package money.hejje.regime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The regime of one session along the PRD section 13 dimensions.
 *
 * @param date               the session (IST)
 * @param asOf               the last bar/quote the labels reflect
 * @param features           the observed numbers behind the labels (EMA values, ADX, percentiles, gap %, ...)
 * @param evidence           templated sentences explaining each label (README rule 11)
 * @param classifierVersion  rule set version that produced the labels
 * @param finalLabel         true once the session closed (intraday snapshots are partial)
 */
public record RegimeSnapshot(LocalDate date, Instant asOf, Trend trend, Volatility volatility, Opening opening, Breadth breadth,
        IntradayStructure intradayStructure, EventEnvironment eventEnvironment, Map<String, Object> features, List<String> evidence,
        String classifierVersion, boolean finalLabel) {

    public RegimeSnapshot {
        features = features == null ? Map.of() : Map.copyOf(features);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static RegimeSnapshot unknown(LocalDate date, Instant asOf, String classifierVersion, String reason) {
        return new RegimeSnapshot(date, asOf, Trend.UNKNOWN, Volatility.UNKNOWN, Opening.UNKNOWN, Breadth.UNKNOWN, IntradayStructure.UNKNOWN,
                EventEnvironment.UNKNOWN, Map.of(), List.of(reason), classifierVersion, false);
    }

    /** True when no dimension could be labelled. */
    public boolean isUnknown() {
        return trend == Trend.UNKNOWN && volatility == Volatility.UNKNOWN && opening == Opening.UNKNOWN && breadth == Breadth.UNKNOWN
                && intradayStructure == IntradayStructure.UNKNOWN;
    }

    /** {@code trend × volatility}, the default grouping key for regime-conditional statistics. */
    public String key() {
        return trend + " × " + volatility;
    }

    /**
     * Whether a strategy {@code regime_preferences} key (docs/strategy-dsl.md) describes this snapshot. Grouped keys:
     * {@code trending}, {@code trending_up}, {@code trending_down}, {@code ranging}, {@code volatile}, {@code quiet},
     * {@code gap}, {@code expiry}; any lower-case label of a dimension ({@code strong_up}, {@code trend_day}, ...) matches
     * exactly.
     */
    public boolean matches(String preferenceKey) {
        String key = preferenceKey.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "trending" -> TRENDING.contains(trend);
            case "trending_up", "uptrend" -> trend == Trend.UP || trend == Trend.STRONG_UP;
            case "trending_down", "downtrend" -> trend == Trend.DOWN || trend == Trend.STRONG_DOWN;
            case "ranging", "range" -> trend == Trend.RANGE;
            case "volatile" -> volatility == Volatility.HIGH || volatility == Volatility.EXTREME;
            case "quiet", "low_volatility" -> volatility == Volatility.VERY_LOW || volatility == Volatility.LOW;
            case "gap" -> opening == Opening.GAP_UP || opening == Opening.GAP_DOWN || opening == Opening.GAP_CONTINUATION
                    || opening == Opening.GAP_REJECTION;
            case "expiry" -> eventEnvironment == EventEnvironment.EXPIRY_SESSION;
            default -> key.equals(trend.name().toLowerCase(Locale.ROOT)) || key.equals(volatility.name().toLowerCase(Locale.ROOT))
                    || key.equals(opening.name().toLowerCase(Locale.ROOT)) || key.equals(breadth.name().toLowerCase(Locale.ROOT))
                    || key.equals(intradayStructure.name().toLowerCase(Locale.ROOT)) || key.equals(eventEnvironment.name().toLowerCase(Locale.ROOT));
        };
    }

    private static final Set<Trend> TRENDING = Set.of(Trend.STRONG_UP, Trend.UP, Trend.DOWN, Trend.STRONG_DOWN);
}
