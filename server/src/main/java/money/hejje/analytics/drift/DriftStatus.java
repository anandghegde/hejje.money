package money.hejje.analytics.drift;

/**
 * Drift status of one deployment, in increasing severity. {@code INSUFFICIENT_DATA} (fewer trades than
 * {@code hejje.drift.min-trades}, or no backtest to compare with) never triggers an action.
 */
public enum DriftStatus {
    INSUFFICIENT_DATA, HEALTHY, WATCH, DEGRADING, FAILED;

    public boolean worseThan(DriftStatus other) {
        return other == null || ordinal() > other.ordinal();
    }
}
