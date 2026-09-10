package money.hejje.agent;

import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;

/**
 * Who is calling a tool and in which session. Scopes come from {@code principal} (the credential), never from prompt
 * content. {@code idempotencyKey} is passed through for transactional tools; {@code clientSource} labels audit rows.
 */
public record ToolContext(HejjePrincipal principal, UUID sessionId, String idempotencyKey, String clientSource) {

    public ToolContext {
        if (principal == null || sessionId == null) {
            throw new IllegalArgumentException("principal and sessionId are required");
        }
        clientSource = clientSource == null ? "agent" : clientSource;
    }
}
