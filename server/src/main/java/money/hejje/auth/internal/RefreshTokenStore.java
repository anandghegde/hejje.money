package money.hejje.auth.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenStore {

    public record RefreshToken(UUID id, UUID userId, String tokenHash, Instant expiresAt, Instant revokedAt) {}

    private final JdbcClient jdbc;

    RefreshTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RefreshToken token) {
        jdbc.sql("INSERT INTO refresh_token (id, user_id, token_hash, expires_at) VALUES (:id, :user, :hash, :exp)")
                .param("id", token.id()).param("user", token.userId()).param("hash", token.tokenHash())
                .param("exp", token.expiresAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<RefreshToken> findByHash(String tokenHash) {
        return jdbc.sql("SELECT id, user_id, token_hash, expires_at, revoked_at FROM refresh_token WHERE token_hash = :h")
                .param("h", tokenHash)
                .query((rs, i) -> new RefreshToken(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("token_hash"), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(rs.getObject("revoked_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null)))
                .optional();
    }

    public void revoke(UUID id, Instant at) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL")
                .param("at", at.atOffset(ZoneOffset.UTC)).param("id", id).update();
    }
}
