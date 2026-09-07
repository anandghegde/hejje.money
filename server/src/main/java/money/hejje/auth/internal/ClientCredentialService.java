package money.hejje.auth.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.HejjeClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates, lists and revokes scoped API keys. The plaintext key is returned exactly once. */
@Service
public class ClientCredentialService {

    public record Created(UUID id, String name, String key, Set<String> scopes, Instant expiresAt) {}

    public record Summary(UUID id, String name, String keyPrefix, Set<String> scopes, Instant createdAt,
            Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {}

    private final ClientCredentialStore store;
    private final AuditService audit;
    private final HejjeClock clock;

    ClientCredentialService(ClientCredentialStore store, AuditService audit, HejjeClock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Created create(String name, Iterable<String> requestedScopes, Instant expiresAt, HejjePrincipal actor) {
        Set<String> scopes = ScopeCatalog.validate(requestedScopes);
        Instant now = clock.now();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        ApiKeys.Generated key = ApiKeys.generate();
        UUID id = Ids.newId();
        store.insert(new ClientCredentialStore.ClientCredential(id, name, key.prefix(), key.secretHash(), scopes, now, expiresAt, null, null));
        audit.record(AuditEvent.of(AuditEventType.CLIENT_CREATED, ActorType.USER).withActorId(actor.name())
                .withPayload(Map.of("clientId", id.toString(), "name", name, "scopes", List.copyOf(scopes), "keyPrefix", key.prefix())));
        return new Created(id, name, key.plaintextKey(), scopes, expiresAt);
    }

    public List<Summary> list() {
        return store.findAll().stream().map(ClientCredentialService::summary).toList();
    }

    @Transactional
    public boolean revoke(UUID id, HejjePrincipal actor) {
        boolean revoked = store.revoke(id, clock.now());
        if (revoked) {
            audit.record(AuditEvent.of(AuditEventType.CLIENT_REVOKED, ActorType.USER).withActorId(actor.name())
                    .withPayload(Map.of("clientId", id.toString())));
        }
        return revoked;
    }

    public boolean exists(UUID id) {
        return store.findById(id).isPresent();
    }

    private static Summary summary(ClientCredentialStore.ClientCredential c) {
        return new Summary(c.id(), c.name(), c.keyPrefix(), c.scopes(), c.createdAt(), c.expiresAt(), c.revokedAt(), c.lastUsedAt());
    }
}
