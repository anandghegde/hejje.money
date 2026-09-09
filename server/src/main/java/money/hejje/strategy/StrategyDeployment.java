package money.hejje.strategy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ExecutionMode;

/**
 * A strategy version deployed on concrete instruments in one execution mode.
 *
 * @param autonomyLevel PRD section 27 level; 0 to 3 in Phase 2
 * @param params        deployment parameters, for example {@code risk_rupees}
 */
public record StrategyDeployment(UUID id, UUID versionId, UUID strategyId, ExecutionMode mode, List<UUID> instrumentIds,
        int autonomyLevel, boolean enabled, Map<String, Object> params, Instant createdAt, Instant pausedAt, String pauseReason) {

    public StrategyDeployment {
        instrumentIds = List.copyOf(instrumentIds);
        params = Map.copyOf(params);
    }
}
