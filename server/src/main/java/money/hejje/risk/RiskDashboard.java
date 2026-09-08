package money.hejje.risk;

import java.math.BigDecimal;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;

/** Account risk dashboard (PRD section 54). */
public record RiskDashboard(
        ExecutionMode mode,
        Money realizedPnl,
        Money unrealizedPnl,
        Money netPnl,
        Money dailyLossLimit,
        Money grossExposure,
        Money maxGrossExposure,
        int openPositions,
        int maxOpenPositions,
        int tradesToday,
        int maxTradesPerDay,
        int consecutiveLosses,
        BigDecimal marginUsedPct,
        boolean killSwitchStopNewOrders) {
}
