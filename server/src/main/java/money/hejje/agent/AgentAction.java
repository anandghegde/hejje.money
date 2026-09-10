package money.hejje.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** One recorded tool call. {@code outputSummary} is the output when small, else a size note with the ids it contained. */
public record AgentAction(UUID id, UUID sessionId, String tool, JsonNode input, JsonNode outputSummary, boolean scopeOk, String status, String error, long latencyMs,
        String correlationId, Instant ts) {
}
