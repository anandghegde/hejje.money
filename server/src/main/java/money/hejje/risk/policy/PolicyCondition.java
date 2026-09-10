package money.hejje.risk.policy;

/** The conditions a rule can test (parameters in the rule's {@code params}). */
public enum PolicyCondition {
    /** Net P&L today at or below minus {@code lossLimitPct}% (default 100) of the daily loss limit. */
    DAILY_LOSS_EXCEEDED,
    /** An agent acting on a strategy whose autonomy level is below {@code minLevel} (default 2, "prepare"). */
    AUTONOMY_BELOW_PREPARE,
    /** An autonomy level above {@code maxLevel} (default 3): levels 4-5 are Phase 5. */
    AUTONOMY_ABOVE_PHASE,
    /** The strategy version is not LIVE yet. */
    NEW_STRATEGY_VERSION,
    /** The instrument's event risk is HIGH. */
    EVENT_RISK_HIGH,
    /** The Hejje Score is below {@code threshold} (default 80). */
    SCORE_BELOW,
    ACTOR_AGENT,
    ACTOR_USER,
    ACTOR_STRATEGY,
    ALWAYS
}
