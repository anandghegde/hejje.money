package money.hejje.risk.policy;

/** The conditions a rule can test (parameters in the rule's {@code params}). */
public enum PolicyCondition {
    /** Net P&L today at or below minus {@code lossLimitPct}% (default 100) of the daily loss limit. */
    DAILY_LOSS_EXCEEDED,
    /** An agent acting on a strategy whose autonomy level is below {@code minLevel} (default 2, "prepare"). */
    AUTONOMY_BELOW_PREPARE,
    /** An autonomy level above {@code maxLevel} (default 3). No longer seeded: levels 4-5 are decided by AUTO_ELIGIBLE (M5.2). */
    AUTONOMY_ABOVE_PHASE,
    /**
     * A STRATEGY actor on a deployment at autonomy {@code minLevel} (default 4) or more whose version qualifies for
     * automation, with a known score (plan M5.2). The only condition a rule may ALLOW on.
     */
    AUTO_ELIGIBLE,
    /** The deployment's daily budget (max trades, max realized loss) is used up. */
    DEPLOYMENT_BUDGET_EXCEEDED,
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
