package money.hejje.strategy;

/**
 * A bot's simulation record, supplied by the harness module (plan M7.5): a bot's backing strategy (family bot) may be
 * deployed in PAPER only after enough SIM sessions with positive expectancy.
 */
public interface BotPromotionEvidence {

    /** Null when the version may be deployed in PAPER; otherwise why not. */
    String refusePaper(StrategyVersion version);
}
