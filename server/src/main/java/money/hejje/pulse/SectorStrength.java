package money.hejje.pulse;

/**
 * One sector row of the Market Pulse.
 *
 * @param changePct   the sector index's change since the previous close, percent (null when no data)
 * @param relativePct change relative to the market index, percentage points (null when either is missing)
 */
public record SectorStrength(String name, String symbol, Label label, Double changePct, Double relativePct) {

    public enum Label { STRONG, NEUTRAL, WEAK, UNKNOWN }
}
