package money.hejje.calibration;

/**
 * How a prediction's outcome is decided (docs/calibration.md, fixed before any result is looked at). Every rule starts at
 * the first M1 bar opening at or after the decision (its open is the reference price), so no outcome uses data from
 * before the decision, and every window ends at the session close at the latest.
 */
public enum LabelRule {
    /** Entry setups: 1 when +1R is reached before −1R within the entry horizon; both in one bar count as −1R first. */
    ENTRY_1R,
    /** Direction answers: sign of the return from the reference price over the direction horizon, against the side asked. */
    DIRECTION,
    /** News direction: sign of the return from the first price after the decision to the close of that price's session. */
    DIRECTION_NEXT_CLOSE,
    /** Exit answers: 1 when over the exit horizon the price moves against the position by more than it moves for it. */
    EXIT
}
