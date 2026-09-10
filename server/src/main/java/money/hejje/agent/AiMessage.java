package money.hejje.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stored turn: the user's question, or the assistant's answer with its grounding and tool trace. */
public record AiMessage(UUID id, UUID conversationId, int seq, String role, String content, String flow, String profile, Grounding grounding,
        List<AiTraceStep> trace, Integer steps, Instant createdAt) {
}
