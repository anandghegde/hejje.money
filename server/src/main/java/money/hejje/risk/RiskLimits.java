package money.hejje.risk;

import java.math.BigDecimal;
import java.time.LocalTime;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;

/**
 * Editable risk limits, one row per {@link ExecutionMode} (PRD section 31). Money fields are stored in paise.
 * Plan M9.7: {@code lossStreakMode} BLOCK stops new entries at {@code maxConsecutiveLosses}; ALLOWANCE lets
 * {@code lossStreakAllowance} more entries through once today's consecutive losses reach it or the day's net falls to
 * −{@code allowanceDrawdown}. {@code tradesPerDayWhenGreen} UNLIMITED lifts {@code maxTradesPerDay} while the day's net is
 * at or above zero.
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
        int maxConsecutiveLosses,
        LossStreakMode lossStreakMode,
        Money allowanceDrawdown,
        int lossStreakAllowance,
        TradesWhenGreen tradesPerDayWhenGreen) {

    public enum LossStreakMode { BLOCK, ALLOWANCE }

    public enum TradesWhenGreen { LIMIT, UNLIMITED }

    public RiskLimits {
        lossStreakMode = lossStreakMode == null ? LossStreakMode.BLOCK : lossStreakMode;
        allowanceDrawdown = allowanceDrawdown == null ? Money.ofRupees(500) : allowanceDrawdown;
        tradesPerDayWhenGreen = tradesPerDayWhenGreen == null ? TradesWhenGreen.LIMIT : tradesPerDayWhenGreen;
    }

    /** The limits before plan M9.7: BLOCK on the loss streak, the trades-per-day limit always on. */
    public RiskLimits(ExecutionMode mode, Money maxLossPerDay, Money maxRealizedLoss, Money maxTotalLossInclUnrealized, Money maxCapitalDeployed,
            BigDecimal maxMarginUtilizationPct, int maxOpenPositions, Money maxGrossExposure, int maxTradesPerDay, Money maxRiskPerTrade, int maxQuantity,
            Money maxNotional, BigDecimal minRewardRisk, boolean mandatoryStop, BigDecimal maxStopDistancePct, LocalTime noNewTradesAfter,
            boolean noAveragingDown, int noReentryMinutes, int maxConsecutiveLosses) {
        this(mode, maxLossPerDay, maxRealizedLoss, maxTotalLossInclUnrealized, maxCapitalDeployed, maxMarginUtilizationPct, maxOpenPositions,
                maxGrossExposure, maxTradesPerDay, maxRiskPerTrade, maxQuantity, maxNotional, minRewardRisk, mandatoryStop, maxStopDistancePct,
                noNewTradesAfter, noAveragingDown, noReentryMinutes, maxConsecutiveLosses, LossStreakMode.BLOCK, Money.ofRupees(500), 4,
                TradesWhenGreen.LIMIT);
    }
}
