package money.hejje.notify;

/** PRD 58 notification types with their default severity. */
public enum NotificationType {
    SIGNAL_GENERATED(Severity.INFO),
    HIGH_SCORE_SETUP(Severity.INFO),
    ORDER_REJECTED(Severity.WARNING),
    STOP_TRIGGERED(Severity.WARNING),
    POSITION_CLOSED(Severity.INFO),
    DAILY_RISK_THRESHOLD(Severity.CRITICAL),
    KILL_SWITCH(Severity.CRITICAL),
    BROKER_DISCONNECTED(Severity.CRITICAL),
    SERVER_UNHEALTHY(Severity.CRITICAL),
    STATIC_IP_MISMATCH(Severity.CRITICAL),
    STRATEGY_DRIFT(Severity.WARNING),
    MAJOR_EVENT_APPROACHING(Severity.WARNING),
    NEWS_CONTEXT_CHANGED(Severity.INFO),
    APPROVAL_REQUESTED(Severity.INFO),
    LLM_BUDGET_EXCEEDED(Severity.WARNING),
    MARKET_CONDITION_CHANGED(Severity.INFO),
    ENTERED_BUY_ZONE(Severity.INFO),
    NEAR_PIVOT(Severity.INFO),
    SETUP_STOPPED(Severity.INFO),
    SETUP_HIT_GOAL(Severity.INFO),
    DAILY_CONTEXT_DIGEST(Severity.INFO),
    /** Plan M11.2: an open delivery position has no confirmed GTT at the broker. */
    GTT_MISSING(Severity.CRITICAL),
    /** Plan M11.2: a GTT at the broker has the wrong quantity or stop, or protects no open position (orphan). */
    GTT_MISMATCH(Severity.WARNING),
    /** Plan M11.6: a swing position's GTT was placed at the broker. */
    GTT_PLACED(Severity.INFO),
    /** Plan M11.6: a swing (delivery) entry filled. */
    SWING_ENTRY_FILLED(Severity.INFO),
    /** Plan M11.6: a swing position's GTT stop fired (a gap through it fills at the open). */
    SWING_STOP_HIT(Severity.WARNING),
    /** Plan M11.6: a swing position's GTT goal fired. */
    SWING_GOAL_HIT(Severity.INFO),
    /** Plan M11.6: a swing position reaches its holding limit and is closed at the next open. */
    SWING_TIME_EXIT_DUE(Severity.WARNING),
    TEST(Severity.INFO);

    private final Severity severity;

    NotificationType(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }

    public enum Severity { INFO, WARNING, CRITICAL }

    public enum Channel { IN_APP, EMAIL, TELEGRAM }
}
