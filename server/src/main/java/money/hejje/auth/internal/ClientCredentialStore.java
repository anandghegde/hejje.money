package money.hejje.auth.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClientCredentialStore {

    public record ClientCredential(UUID id, String name, String keyPrefix, String secretHash, Set<String> scopes,
            Instant createdAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt, UUID botId) {

        public boolean isActive(Instant now) {
            return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
        }
    }

    private final JdbcClient jdbc;

    ClientCredentialStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ClientCredential c) {
        jdbc.sql("""
                INSERT INTO client_credential (id, name, key_prefix, secret_hash, scopes, created_at, expires_at, bot_id)
                VALUES (:id, :name, :prefix, :hash, :scopes, :created, :expires, :bot)
                """)
                .param("id", c.id()).param("name", c.name()).param("prefix", c.keyPrefix()).param("hash", c.secretHash()).param("bot", c.botId())
                .param("scopes", c.scopes().toArray(String[]::new))
                .param("created", c.createdAt().atOffset(ZoneOffset.UTC))
                .param("expires", c.expiresAt() == null ? null : c.expiresAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<ClientCredential> findByPrefix(String keyPrefix) {
        return jdbc.sql("SELECT * FROM client_credential WHERE key_prefix = :p").param("p", keyPrefix)
                .query(this::map).optional();
    }

    public Optional<ClientCredential> findById(UUID id) {
        return jdbc.sql("SELECT * FROM client_credential WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public List<ClientCredential> findAll() {
        return jdbc.sql("SELECT * FROM client_credential ORDER BY created_at DESC").query(this::map).list();
    }

    public boolean revoke(UUID id, Instant at) {
        return jdbc.sql("UPDATE client_credential SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL")
                .param("at", at.atOffset(ZoneOffset.UTC)).param("id", id).update() == 1;
    }

    public void touchLastUsed(UUID id, Instant at) {
        jdbc.sql("UPDATE client_credential SET last_used_at = :at WHERE id = :id")
                .param("at", at.atOffset(ZoneOffset.UTC)).param("id", id).update();
    }

    private ClientCredential map(ResultSet rs, int i) throws SQLException {
        Array scopes = rs.getArray("scopes");
        return new ClientCredential(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("key_prefix"),
                rs.getString("secret_hash"),
                new LinkedHashSet<>(Arrays.asList((String[]) scopes.getArray())),
                instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "revoked_at"), instant(rs, "last_used_at"),
                rs.getObject("bot_id", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
