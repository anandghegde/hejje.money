package money.hejje.auth.internal;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.auth.AuthProperties;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Password login, refresh-token rotation and logout. */
@Service
public class AuthService {

    /** A successful login or refresh: the JWT plus the new refresh token (plaintext, for the cookie). */
    public record Session(String accessToken, Instant accessTokenExpiresAt, String refreshToken, Instant refreshTokenExpiresAt) {}

    private final UserStore users;
    private final RefreshTokenStore refreshTokens;
    private final TokenService tokens;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final AuthTime clock;
    private final AuthProperties properties;

    AuthService(UserStore users, RefreshTokenStore refreshTokens, TokenService tokens, PasswordEncoder encoder,
            AuditService audit, AuthTime clock, AuthProperties properties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public Optional<Session> login(String username, String password, String clientSource) {
        Optional<UserStore.User> user = users.findByUsername(username)
                .filter(u -> encoder.matches(password, u.passwordHash()));
        if (user.isEmpty()) {
            audit.record(AuditEvent.of(AuditEventType.AUTH_LOGIN_FAILED, ActorType.USER).withActorId(username)
                    .withClientSource(clientSource).withPayload(Map.of("username", username)));
            return Optional.empty();
        }
        audit.record(AuditEvent.of(AuditEventType.AUTH_LOGIN, ActorType.USER).withActorId(username).withClientSource(clientSource));
        return Optional.of(newSession(user.get()));
    }

    @Transactional
    public Optional<Session> refresh(String refreshToken) {
        Instant now = clock.now();
        Optional<RefreshTokenStore.RefreshToken> stored = refreshTokens.findByHash(tokens.hashRefreshToken(refreshToken))
                .filter(t -> t.revokedAt() == null && t.expiresAt().isAfter(now));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        refreshTokens.revoke(stored.get().id(), now);
        return users.findById(stored.get().userId()).map(this::newSession);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        refreshTokens.findByHash(tokens.hashRefreshToken(refreshToken))
                .ifPresent(t -> refreshTokens.revoke(t.id(), clock.now()));
    }

    private Session newSession(UserStore.User user) {
        Instant now = clock.now();
        HejjePrincipal principal = new HejjePrincipal(user.id(), user.username(), HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String refresh = tokens.newRefreshToken();
        Instant refreshExpiry = now.plus(properties.refreshTokenTtl());
        refreshTokens.insert(new RefreshTokenStore.RefreshToken(Ids.newId(), user.id(), tokens.hashRefreshToken(refresh), refreshExpiry, null));
        return new Session(tokens.issueAccessToken(principal), tokens.accessTokenExpiry(now), refresh, refreshExpiry);
    }
}
