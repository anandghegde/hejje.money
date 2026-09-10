package money.hejje.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent proposal awaiting (or past) a human decision (plan M4.4). {@code proposal} is the sized order or the action's
 * parameters, {@code risk} the dry-run risk decision, {@code policy} the policy result at proposal time, {@code result}
 * what execution returned (order id, state) or why it failed.
 */
public record Approval(UUID id, ApprovalKind kind, ApprovalStatus status, String mode, UUID intentId, UUID signalId, UUID strategyId, UUID instrumentId,
        String instrument, UUID orderId, UUID requestedBySession, String requestedBy, UUID requestedByPrincipal, String requestedByType,
        @JsonIgnore String requestKey, String summary, String rationale, JsonNode proposal, JsonNode risk, JsonNode policy, Instant createdAt, Instant expiresAt,
        String decidedBy, Instant decidedAt, @JsonIgnore String decisionKey, String decisionNote, JsonNode result) {
}
