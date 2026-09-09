package money.hejje.scoring;

/**
 * A bounded score adjuster (PRD section 14 components beyond the base backtest score). Phase 2 ships technical
 * compatibility and recent paper/live performance; Phase 3 adds regime, news and event adjusters as more beans.
 */
public interface ScoreAdjuster {

    /** Stable display name, for example {@code Current regime}. */
    String name();

    /** Lower bound of the delta this adjuster may apply (negative or zero). */
    int min();

    /** Upper bound of the delta (positive or zero). */
    int max();

    Adjustment adjust(ScoreContext context);
}
