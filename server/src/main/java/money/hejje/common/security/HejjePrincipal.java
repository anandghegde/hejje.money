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
 */
public record HejjePrincipal(UUID id, String name, Type type, Set<String> scopes) {

    public enum Type { USER, CLIENT }

    public HejjePrincipal {
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
