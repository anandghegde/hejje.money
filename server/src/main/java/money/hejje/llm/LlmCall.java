package money.hejje.llm;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code llm_call}: what was asked of which model, at what cost. Prompts are hashed, not stored. */
public record LlmCall(UUID id, Instant at, String profile, String provider, String model, String purpose, String promptVersion, String promptHash,
        Integer inputTokens, Integer outputTokens, Long costEstimatePaise, long latencyMs, String correlationId, String status, String error) {
}
