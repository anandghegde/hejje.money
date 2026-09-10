package money.hejje.agent;

import java.time.Instant;
import java.util.UUID;

/** One agent session: a credential (or the local user) working on one purpose ({@code direct}, {@code mcp}, {@code chat}). */
public record AgentSession(UUID id, UUID clientCredentialId, String principalType, UUID principalId, String principalName, String profile, String purpose,
        Instant startedAt, Instant endedAt) {
}
