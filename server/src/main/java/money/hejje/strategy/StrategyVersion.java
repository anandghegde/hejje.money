package money.hejje.strategy;

import java.time.Instant;
import java.util.UUID;

/** One immutable strategy version. Only {@code status} ever changes after insert (DB trigger enforces this). */
public record StrategyVersion(UUID id, UUID strategyId, int version, String definitionYaml, StrategyDefinition definition,
        String definitionHash, String changeNote, UUID parentVersionId, String createdBy, Instant createdAt, VersionStatus status) {
}
