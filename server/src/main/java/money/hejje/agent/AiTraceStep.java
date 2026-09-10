package money.hejje.agent;

import java.util.UUID;

/** One tool call made while answering, as shown in the trace panel. */
public record AiTraceStep(UUID actionId, String tool, String status, String requiredScope, long latencyMs, String error) {
}
