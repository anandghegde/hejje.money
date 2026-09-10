package money.hejje.agent;

import java.util.List;
import java.util.UUID;

/** One answered question: the answer, its grounding check, the tool trace, and how it was produced. */
public record AiTurn(UUID conversationId, UUID messageId, String answer, Grounding grounding, List<AiTraceStep> trace, int steps, String profile, String flow,
        boolean stepLimitReached) {
}
