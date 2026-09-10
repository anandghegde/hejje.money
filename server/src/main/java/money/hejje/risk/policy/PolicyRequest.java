package money.hejje.risk.policy;

import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;

/**
 * The action under decision and its context. {@code autonomyLevel} is the deployment's (or the account's) level,
 * {@code eventRisk} the instrument's level name (LOW, MEDIUM, HIGH), {@code score} the Hejje Score when known.
 */
public record PolicyRequest(PolicyAction action, ActorType actorType, ExecutionMode mode, Integer autonomyLevel, String eventRisk, Integer score,
        boolean newStrategyVersion, UUID strategyId, UUID instrumentId) {
}
