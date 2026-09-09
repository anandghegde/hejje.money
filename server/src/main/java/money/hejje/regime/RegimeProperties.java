package money.hejje.regime;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Regime engine settings and rule thresholds ({@code hejje.regime.*}, config/regime.yaml, docs/regime.md).
 *
 * @param enabled            when false every label is {@code UNKNOWN} and nothing is stored
 * @param classifierVersion  bump when a rule or threshold changes; stored rows carry it and are relabelled on startup
 * @param labelOnStartup     relabel sessions whose stored version differs (and label missing ones) after boot
 * @param indexSymbol        the market index the trend, opening and structure rules read
 * @param vixSymbol          the volatility index
 * @param universe           YAML resource listing the constituents used for breadth ({@code symbols:} list)
 * @param lookbackSessions   trailing window for percentiles
 * @param minSessions        below this many daily bars volatility percentiles are {@code UNKNOWN}
 * @param intradaySnapshot   how often an intraday snapshot is stored during the session (also the cache lifetime)
 * @param adjuster           points used by the regime compatibility score adjuster
 */
@ConfigurationProperties("hejje.regime")
public record RegimeProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1") String classifierVersion,
        @DefaultValue("true") boolean labelOnStartup,
        @DefaultValue("INDEX:NIFTY 50") String indexSymbol,
        @DefaultValue("INDEX:INDIA VIX") String vixSymbol,
        @DefaultValue("classpath:universe/nifty50.yaml") String universe,
        @DefaultValue("250") int lookbackSessions,
        @DefaultValue("60") int minSessions,
        @DefaultValue("PT5M") Duration intradaySnapshot,
        @DefaultValue TrendRules trend,
        @DefaultValue VolatilityRules volatility,
        @DefaultValue OpeningRules opening,
        @DefaultValue BreadthRules breadth,
        @DefaultValue StructureRules structure,
        @DefaultValue AdjusterPoints adjuster) {

    /**
     * @param trendAdx       ADX at or above which a bullish/bearish EMA structure counts as a trend (below: RANGE)
     * @param strongAdx      ADX for STRONG_* (together with the slope)
     * @param strongSlopePct EMA-fast change over {@code slopeSessions} sessions, in percent, for STRONG_*
     */
    public record TrendRules(@DefaultValue("20") int emaFast, @DefaultValue("50") int emaSlow, @DefaultValue("14") int adxPeriod,
            @DefaultValue("5") int slopeSessions, @DefaultValue("18") double trendAdx, @DefaultValue("25") double strongAdx,
            @DefaultValue("1.0") double strongSlopePct) {
    }

    /** Percentile boundaries (0-100) of the combined VIX / ATR-ratio percentile. */
    public record VolatilityRules(@DefaultValue("14") int atrPeriod, @DefaultValue("10") double veryLowPct, @DefaultValue("30") double lowPct,
            @DefaultValue("70") double highPct, @DefaultValue("90") double extremePct) {
    }

    /**
     * @param flatGapPct        |gap| below this percent of the previous close is FLAT
     * @param openingRangeMinutes minutes after the open whose close refines the gap label
     * @param rejectionFraction  a close back through the open by this fraction of the gap is GAP_REJECTION
     */
    public record OpeningRules(@DefaultValue("0.2") double flatGapPct, @DefaultValue("15") int openingRangeMinutes,
            @DefaultValue("0.5") double rejectionFraction) {
    }

    /** Advance ratio boundaries; {@code minCoverage} is the share of constituents that must have data. */
    public record BreadthRules(@DefaultValue("0.6") double minCoverage, @DefaultValue("0.75") double strongPositive,
            @DefaultValue("0.6") double positive, @DefaultValue("0.4") double negative, @DefaultValue("0.25") double strongNegative) {
    }

    /**
     * @param minBars                  intraday bars needed before a (progressive) label is given
     * @param openingRangeMinutes      opening range used for range expansion
     * @param trendRangeExpansion      day range / opening range for TREND_DAY
     * @param trendClosePosition       close within the day range (from the top or bottom) for TREND_DAY
     * @param trendMaxVwapCrosses      at most this many session-average crosses for TREND_DAY
     * @param chopMinVwapCrosses       at least this many crosses for HIGH_VOLATILITY_CHOP
     * @param chopMinRangeAtr          day range / daily ATR for HIGH_VOLATILITY_CHOP
     * @param compressionMaxRangeAtr   day range / daily ATR at or below which the day is LOW_VOLATILITY_COMPRESSION
     * @param reversalMinRangeAtr      day range / daily ATR needed for REVERSAL_DAY
     * @param reversalMinExcursion     opposite excursion as a fraction of the day range for REVERSAL_DAY
     */
    public record StructureRules(@DefaultValue("6") int minBars, @DefaultValue("15") int openingRangeMinutes,
            @DefaultValue("2.0") double trendRangeExpansion, @DefaultValue("0.8") double trendClosePosition,
            @DefaultValue("2") int trendMaxVwapCrosses, @DefaultValue("4") int chopMinVwapCrosses, @DefaultValue("1.0") double chopMinRangeAtr,
            @DefaultValue("0.5") double compressionMaxRangeAtr, @DefaultValue("0.8") double reversalMinRangeAtr,
            @DefaultValue("0.5") double reversalMinExcursion) {
    }

    /**
     * @param preferredPoints   added per matched {@code preferred} preference
     * @param avoidPoints       subtracted per matched {@code avoid} preference
     * @param similarFullPoints points at {@code similarFullDiffR} expectancy difference (scaled linearly, clipped)
     * @param minSimilarTrades  below this many similar-regime trades the expectancy part is 0
     */
    public record AdjusterPoints(@DefaultValue("3") int preferredPoints, @DefaultValue("6") int avoidPoints, @DefaultValue("7") int similarFullPoints,
            @DefaultValue("0.5") double similarFullDiffR, @DefaultValue("10") int minSimilarTrades) {
    }
}
