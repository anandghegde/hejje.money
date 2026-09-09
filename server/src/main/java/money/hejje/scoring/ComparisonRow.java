package money.hejje.scoring;

import java.util.UUID;

/** One column of the PRD section 21 comparison table (metrics from the version's base backtest, overall). */
public record ComparisonRow(UUID versionId, UUID strategyId, String slug, int version, String status, UUID backtestId, Integer trades, Double winRate,
        Double profitFactor, Double expectancyR, Double maxDrawdownR, String similarRegimePerformance, Integer hejjeScore) {
}
