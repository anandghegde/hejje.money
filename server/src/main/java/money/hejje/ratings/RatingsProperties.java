package money.hejje.ratings;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Daily context settings ({@code hejje.ratings.*}, config/ratings.yaml, docs/ratings.md).
 *
 * @param enabled          when false nothing is refreshed or computed and the API answers 503
 * @param universe         universe file name under {@code config/universe} (D1 only)
 * @param engineVersion    stored with every row; bump when a formula or threshold changes (old rows are kept)
 * @param refreshSessions  trading days of D1 candles the evening refresh re-fetches (an idempotent upsert)
 */
@ConfigurationProperties("hejje.ratings")
public record RatingsProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("nifty500") String universe,
        @DefaultValue("1") String engineVersion,
        @DefaultValue("5") int refreshSessions,
        @DefaultValue Formula formula,
        @DefaultValue Bases bases,
        @DefaultValue Lists lists) {

    /**
     * @param quarterSessions    sessions per RS horizon step; the horizons are 1 to {@code rsWeights.size()} quarters
     * @param rsWeights          weight per horizon, newest quarter first
     * @param adSessions         accumulation/distribution window
     * @param highLowSessions    window of the off-high / off-low reference
     * @param volumeSessions     window of average volume, up/down volume and turnover
     * @param minGroupMembers    a group is ranked when at least this many members have an RS value
     * @param minSessionCoverage share of the universe that must have a candle for a date to count as a session
     */
    public record Formula(@DefaultValue("63") int quarterSessions, @DefaultValue({"0.4", "0.2", "0.2", "0.2"}) List<Double> rsWeights,
            @DefaultValue("65") int adSessions, @DefaultValue("252") int highLowSessions, @DefaultValue("50") int volumeSessions,
            @DefaultValue("3") int minGroupMembers, @DefaultValue CompositeWeights compositeWeights,
            @DefaultValue("0.2") double minSessionCoverage) {
    }

    public record CompositeWeights(@DefaultValue("0.5") double rs, @DefaultValue("0.2") double ad, @DefaultValue("0.15") double group,
            @DefaultValue("0.15") double offHigh) {
    }

    /**
     * Base detection and the trade plan (docs/ratings.md, "Bases"). Percentages are percent, lengths are sessions.
     *
     * @param priorUptrendPct       advance from the lowest low of {@code priorUptrendSessions} into the base's left high
     * @param cupRecoveryPct        the cup's right side must reach this share of the left high
     * @param handleUpperHalf       the handle's low must stay in the upper half of the cup
     * @param doubleBottomRequireUndercut the second low must undercut the first
     * @param doubleBottomMinPeakPct the middle peak must stand this far above the higher of the two lows
     * @param maTouchPct            a low within this percent above a moving average counts as trading down to it
     * @param reversalCloseInRange  a reversal day closes at or above this share of its range
     * @param expireSessions        an untriggered base expires this many sessions after detection
     * @param maxHoldSessions       a triggered setup that hit neither goal nor stop is closed at the close after this many sessions
     */
    public record Bases(@DefaultValue("25") double priorUptrendPct, @DefaultValue("120") int priorUptrendSessions,
            @DefaultValue("25") int flatMinSessions, @DefaultValue("65") int flatMaxSessions, @DefaultValue("15") double flatMaxDepthPct,
            @DefaultValue("35") int cupMinSessions, @DefaultValue("325") int cupMaxSessions, @DefaultValue("12") double cupMinDepthPct,
            @DefaultValue("35") double cupMaxDepthPct, @DefaultValue("90") double cupRecoveryPct, @DefaultValue("5") int handleMinSessions,
            @DefaultValue("30") int handleMaxSessions, @DefaultValue("12") double handleMaxDepthPct, @DefaultValue("true") boolean handleUpperHalf,
            @DefaultValue("35") int doubleBottomMinSessions, @DefaultValue("150") int doubleBottomMaxSessions,
            @DefaultValue("3") double doubleBottomLowsWithinPct, @DefaultValue("false") boolean doubleBottomRequireUndercut,
            @DefaultValue("10") int doubleBottomMinSeparation, @DefaultValue("8") double doubleBottomMinPeakPct,
            @DefaultValue("35") double doubleBottomMaxDepthPct, @DefaultValue("10") int maRisingSessions, @DefaultValue("0.5") double maTouchPct,
            @DefaultValue("0.6") double reversalCloseInRange, @DefaultValue("5") double buyZonePct, @DefaultValue("7") double stopPct,
            @DefaultValue("20") double goalPct, @DefaultValue("8") double reversalGoalPct, @DefaultValue("5") double nearPivotPct,
            @DefaultValue("1.4") double breakoutVolume, @DefaultValue("60") int expireSessions, @DefaultValue("120") int maxHoldSessions) {
    }

    /**
     * @param leaderComposite  Leaders: technical composite at or above this
     * @param leaderRs         ... and RS rating at or above this
     * @param minTurnoverCr    ... and mean daily turnover (crore rupees) at or above this
     * @param moverChangePct   Stocks on the move: absolute change at or above this percent
     * @param moverVolume      ... on at least this multiple of the 50-session average volume
     */
    public record Lists(@DefaultValue("85") int leaderComposite, @DefaultValue("80") int leaderRs, @DefaultValue("10") double minTurnoverCr,
            @DefaultValue("2") double moverChangePct, @DefaultValue("1.5") double moverVolume) {
    }
}
