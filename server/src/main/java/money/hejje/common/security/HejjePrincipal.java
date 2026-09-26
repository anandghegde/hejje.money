package money.hejje.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller: the local user (via JWT) or a client credential (via API key).
 *
 * @param id     user id or client credential id
 * @param name   username or client name
 * @param type   USER or CLIENT
 * @param scopes granted scopes
 * @param botId  the one bot a {@code bot:decide} client credential may decide for; null when unbound (users, other keys)
 */
public record HejjePrincipal(UUID id, String name, Type type, Set<String> scopes, UUID botId) {

    public enum Type { USER, CLIENT }

    public HejjePrincipal {
        scopes = Set.copyOf(scopes);
    }

    public HejjePrincipal(UUID id, String name, Type type, Set<String> scopes) {
        this(id, name, type, scopes, null);
    }

    /** False when this principal is bound to a bot other than {@code botId}. */
    public boolean mayDecideFor(UUID botId) {
        return this.botId == null || this.botId.equals(botId);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
