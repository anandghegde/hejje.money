package money.hejje.recommend;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Side;

/**
 * The PRD section 29 decision object plus the PRD section 20 evidence. {@code regime} carries the trend label, {@code eventRisk}
 * the PRD 18 level and {@code nextEvent} the "Q2 Results — Today 16:00" line; {@code newsBias} arrives with M3.4.
 *
 * @param score            latest Hejje Score for the version on the instrument (null when never scored)
 * @param hardBlocks       reasons execution is impossible right now (risk rejection, kill switch, readiness)
 * @param supportingEvidence PRD 20 "✓" lines, templated from structured evidence
 * @param risks            PRD 20 "⚠" lines
 * @param expectedRewardRupees risk × reward:risk of the target, when a target exists
 */
public record Recommendation(UUID versionId, UUID strategyId, String strategy, int version, UUID deploymentId, UUID instrumentId, String instrument,
        Integer score, Decision decision, Side direction, UUID signalId, String signalStatus, Instant signalValidUntil, BigDecimal entry, BigDecimal stop,
        BigDecimal target, Integer quantity, BigDecimal riskRupees, BigDecimal expectedRewardRupees, String regime, Double newsBias, String eventRisk,
        String nextEvent, List<String> hardBlocks, List<Caution> cautions, List<String> supportingEvidence, List<String> risks, Map<String, Object> backtest,
        Map<String, Object> scoreBreakdown, money.hejje.context.StrategyContext context) {

    public Recommendation {
        hardBlocks = hardBlocks == null ? List.of() : List.copyOf(hardBlocks);
        cautions = cautions == null ? List.of() : List.copyOf(cautions);
        supportingEvidence = supportingEvidence == null ? List.of() : List.copyOf(supportingEvidence);
        risks = risks == null ? List.of() : List.copyOf(risks);
        backtest = backtest == null ? Map.of() : Map.copyOf(backtest);
        scoreBreakdown = scoreBreakdown == null ? Map.of() : Map.copyOf(scoreBreakdown);
    }
}
