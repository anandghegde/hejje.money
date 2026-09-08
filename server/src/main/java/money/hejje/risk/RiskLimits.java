package money.hejje.risk;

import java.math.BigDecimal;
import java.time.LocalTime;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;

/**
 * Editable risk limits, one row per {@link ExecutionMode} (PRD section 31). Money fields are stored in paise.
 */
public record RiskLimits(
        ExecutionMode mode,
        Money maxLossPerDay,
        Money maxRealizedLoss,
        Money maxTotalLossInclUnrealized,
        Money maxCapitalDeployed,
        BigDecimal maxMarginUtilizationPct,
        int maxOpenPositions,
        Money maxGrossExposure,
        int maxTradesPerDay,
        Money maxRiskPerTrade,
        int maxQuantity,
        Money maxNotional,
        BigDecimal minRewardRisk,
        boolean mandatoryStop,
        BigDecimal maxStopDistancePct,
        LocalTime noNewTradesAfter,
        boolean noAveragingDown,
        int noReentryMinutes,
        int maxConsecutiveLosses) {
}
