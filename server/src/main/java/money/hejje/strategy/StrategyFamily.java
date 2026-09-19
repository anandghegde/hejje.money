package money.hejje.strategy;

/**
 * PRD section 9.1 strategy families, plus {@code BOT} (plan M7.3): the backing strategy of a bot, whose entries are the
 * bot's decisions (its runner manages positions and never evaluates entry rules).
 */
public enum StrategyFamily { TREND, MEAN_REVERSION, INDEX, OPTIONS, BOT }
