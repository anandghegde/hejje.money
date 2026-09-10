package money.hejje.backtest.experiments;

import java.math.BigDecimal;

public record SplitSummary(int trades, double expectancyR, Double profitFactor, double maxDrawdownR, double winRate, BigDecimal netPnl) {
}
