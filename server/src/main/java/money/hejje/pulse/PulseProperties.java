package money.hejje.pulse;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Pulse settings ({@code hejje.pulse.*}, config/pulse.yaml, docs/pulse.md).
 *
 * @param enabled                 when false the pulse is NEUTRAL/WEAK with score 0 and "disabled" evidence
 * @param sectors                 YAML resource listing the sector indices ({@code sectors: [{name, symbol}]})
 * @param indexSymbol             the market index
 * @param vixSymbol               the volatility index
 * @param futuresUnderlying       underlying of the nearest future used for relative volume and basis
 * @param interval                recompute/store cadence during the session (also the cache lifetime)
 * @param weights                 rule name → weight (a rule absent from the map keeps its default weight)
 * @param thresholds              rule thresholds
 */
@ConfigurationProperties("hejje.pulse")
public record PulseProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("classpath:universe/sectors.yaml") String sectors,
        @DefaultValue("INDEX:NIFTY 50") String indexSymbol,
        @DefaultValue("INDEX:INDIA VIX") String vixSymbol,
        @DefaultValue("NIFTY") String futuresUnderlying,
        @DefaultValue("PT1M") Duration interval,
        Map<String, Double> weights,
        @DefaultValue Thresholds thresholds) {

    public PulseProperties {
        weights = weights == null ? Map.of() : Map.copyOf(weights);
    }

    /**
     * @param vwapFullPct        index distance from its session average (percent) worth a full ±1
     * @param dayChangeFullPct   index change since the previous close (percent) worth a full ±1
     * @param rocBars            M5 bars of the momentum rate of change
     * @param rocFullPct         rate of change (percent) worth a full ±1
     * @param vixFullChangePct   VIX change since its previous close (percent) worth a full ∓1
     * @param sectorStrongPct    sector change minus index change (percentage points) for STRONG / WEAK
     * @param basisNeutralPct    futures basis (percent of the index) that counts as neutral carry
     * @param basisFullPct       basis distance from neutral worth a full ±1
     * @param relativeVolumeFull relative volume at which volume confirmation is a full 1
     * @param relativeVolumeSessions sessions averaged for the volume baseline
     * @param bullishScore       |score| at or above which the direction is BULLISH / BEARISH
     * @param moderateScore      |score| for MODERATE strength
     * @param strongScore        |score| for STRONG strength
     */
    public record Thresholds(@DefaultValue("0.3") double vwapFullPct, @DefaultValue("0.75") double dayChangeFullPct, @DefaultValue("12") int rocBars,
            @DefaultValue("0.4") double rocFullPct, @DefaultValue("5") double vixFullChangePct, @DefaultValue("0.5") double sectorStrongPct,
            @DefaultValue("0.1") double basisNeutralPct, @DefaultValue("0.15") double basisFullPct, @DefaultValue("1.5") double relativeVolumeFull,
            @DefaultValue("10") int relativeVolumeSessions, @DefaultValue("20") int bullishScore, @DefaultValue("35") int moderateScore,
            @DefaultValue("60") int strongScore) {
    }

    /** Default weights (docs/pulse.md); overridden per rule by {@code weights}. */
    public static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            "index_trend", 20.0, "index_vs_vwap", 15.0, "day_change", 10.0, "momentum", 10.0, "breadth", 15.0,
            "relative_volume", 5.0, "vix", 10.0, "sectors", 10.0, "futures_basis", 5.0, "gap", 5.0);

    public double weight(String rule) {
        Double w = weights.get(rule);
        return w != null ? w : DEFAULT_WEIGHTS.getOrDefault(rule, 0.0);
    }
}
