package money.hejje.analytics.drift;

/** What a drift status may do (PRD section 25), configured per status in config/drift.yaml. */
public enum DriftAction {
    /** Audit + {@code /ws/events} notification + WARN log (channels arrive with M5.5). */
    ALERT,
    /** The {@code Live-vs-backtest drift} score adjuster applies the status's points. */
    LOWER_SCORE,
    /** Lowers the deployment's size multiplier to {@code hejje.drift.size-multiplier}. */
    REDUCE_SIZE,
    /** Pauses a CONFIRM/AUTO deployment and redeploys the version in PAPER; skipped for PAPER deployments. */
    MOVE_TO_PAPER,
    /** Pauses the deployment ({@code STRATEGY_PAUSED} with the statistics). */
    PAUSE
}
