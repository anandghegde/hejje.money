package money.hejje.strategy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ExecutionMode;

/**
 * A strategy version deployed on concrete instruments in one execution mode.
 *
 * @param autonomyLevel PRD section 27 level 0-5; 4-5 only on PAPER (rehearsal) and AUTO deployments (plan M5.2)
 * @param params        deployment parameters, for example {@code risk_rupees}
 * @param sizeMultiplier scales the risk per trade (1.00 = as configured); lowered by the drift monitor (M5.1)
 */
public record StrategyDeployment(UUID id, UUID versionId, UUID strategyId, ExecutionMode mode, List<UUID> instrumentIds,
        int autonomyLevel, boolean enabled, Map<String, Object> params, Instant createdAt, Instant pausedAt, String pauseReason,
        java.math.BigDecimal sizeMultiplier) {

    public StrategyDeployment {
        instrumentIds = List.copyOf(instrumentIds);
        params = Map.copyOf(params);
        sizeMultiplier = sizeMultiplier == null ? java.math.BigDecimal.ONE.setScale(2) : sizeMultiplier.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
