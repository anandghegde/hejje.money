package money.hejje.agent;

import java.time.Instant;
import java.util.UUID;

public record AiConversation(UUID id, UUID sessionId, UUID principalId, String title, Instant createdAt, Instant updatedAt) {
}
