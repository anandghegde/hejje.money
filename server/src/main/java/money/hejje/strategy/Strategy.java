package money.hejje.strategy;

import java.time.Instant;
import java.util.UUID;

/**
 * A strategy: a named lineage of immutable versions.
 *
 * @param latestVersion  highest version number, or 0 when none
 * @param latestStatus   status of the latest version, or null
 */
public record Strategy(UUID id, String slug, StrategyFamily family, String name, Instant createdAt, Instant retiredAt,
        int latestVersion, UUID latestVersionId, VersionStatus latestStatus) {
}
