package money.hejje.regime;

/** PRD section 13 intraday structure; progressive during the session, final at the close. */
public enum IntradayStructure { TREND_DAY, RANGE_DAY, REVERSAL_DAY, HIGH_VOLATILITY_CHOP, LOW_VOLATILITY_COMPRESSION, UNKNOWN }
