package money.hejje.regime;

/** The daily market call (plan M8.3, docs/regime.md "Market condition"): distribution days, rally attempts and follow-through days on the index. */
public enum MarketCondition { CONFIRMED_UPTREND, UPTREND_UNDER_PRESSURE, RALLY_ATTEMPT, DOWNTREND, UNKNOWN }
