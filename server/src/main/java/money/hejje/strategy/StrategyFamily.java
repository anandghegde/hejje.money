package money.hejje.strategy;

/**
 * PRD section 9.1 strategy families, plus {@code BOT} (plan M7.3): the backing strategy of a bot, whose entries are the
 * bot's decisions (its runner manages positions and never evaluates entry rules), and {@code SWING} (plan M11.4): the
 * backing strategy of a swing deployment, whose entries are the M8.4 trade plans crossing their pivots; it has no
 * intraday runner (the swing module watches the setups and the broker-side GTT protects the delivery position).
 */
public enum StrategyFamily { TREND, MEAN_REVERSION, INDEX, OPTIONS, BOT, SWING }
