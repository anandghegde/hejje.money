package money.hejje.analytics;

import money.hejje.common.Money;

/** One row of a P&L breakdown (PRD section 53). */
public record PnlBucket(String key, String label, int trades, int wins, Money grossPnl, Money fees, Money netPnl, double winRate, Double averageR) {
}
