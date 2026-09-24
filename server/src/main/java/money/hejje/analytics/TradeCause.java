package money.hejje.analytics;

import java.util.Map;

/**
 * Why a closed trade ended the way it did, and how its entry was timed (plan M9.6, docs/analytics.md "Trade cause").
 * Deterministic rules on M1 candles are the source of truth; {@code jevCause} / {@code jevTiming} are Jev's reading
 * beside them when Jev is on (null otherwise).
 *
 * @param mfeR     best move in the trade's favour while it was open, in R
 * @param maeR     worst move against it, in R (zero or negative)
 * @param evidence the numbers each rule used (and {@code partialWindow} when the post-exit window was cut short)
 * @param complete false until the post-exit window has been seen (the completion job, 35 minutes after the close)
 */
public record TradeCause(Cause cause, Timing entryTiming, Double mfeR, Double maeR, Map<String, Object> evidence, String jevCause, String jevTiming,
        boolean complete) {

    public enum Cause { CLEAN_TARGET, NOISE_STOP, THESIS_BREAK, DRIFT, BAD_ENTRY, UNKNOWN }

    public enum Timing { EARLY, GOOD, LATE }

    public TradeCause {
        evidence = evidence == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(evidence));
    }
}
