package money.hejje.risk;

import java.math.BigDecimal;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;

/**
 * Account risk dashboard (PRD section 54). {@code allowanceUsed} / {@code allowance} / {@code allowanceReason}: an
 * ALLOWANCE-mode day's loss-streak allowance once triggered (plan M9.7), null otherwise.
 */
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
        boolean killSwitchStopNewOrders,
        String lossStreakMode,
        Integer allowanceUsed,
        Integer allowance,
        String allowanceReason) {

    public RiskDashboard(ExecutionMode mode, Money realizedPnl, Money unrealizedPnl, Money netPnl, Money dailyLossLimit, Money grossExposure,
            Money maxGrossExposure, int openPositions, int maxOpenPositions, int tradesToday, int maxTradesPerDay, int consecutiveLosses,
            BigDecimal marginUsedPct, boolean killSwitchStopNewOrders) {
        this(mode, realizedPnl, unrealizedPnl, netPnl, dailyLossLimit, grossExposure, maxGrossExposure, openPositions, maxOpenPositions, tradesToday,
                maxTradesPerDay, consecutiveLosses, marginUsedPct, killSwitchStopNewOrders, null, null, null, null);
    }
}
