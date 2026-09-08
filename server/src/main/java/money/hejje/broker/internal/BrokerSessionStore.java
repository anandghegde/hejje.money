package money.hejje.broker.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import money.hejje.broker.BrokerSessionState;
import money.hejje.common.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One row per broker in {@code broker_session}. */
@Repository
public class BrokerSessionStore {

    /** Persistent session row. {@code accessTokenEnc} is ciphertext (see TokenCipher). */
    public record Row(UUID id, String broker, String brokerUserId, String accessTokenEnc, String publicToken, Instant establishedAt,
            Instant expiresAt, BrokerSessionState status, String detail, Instant lastCheckedAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    BrokerSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(String broker) {
        return jdbc.sql("SELECT * FROM broker_session WHERE broker = :broker").param("broker", broker).query(this::map).optional();
    }

    public void connected(String broker, String brokerUserId, String accessTokenEnc, String publicToken, Instant establishedAt,
            Instant expiresAt, Instant now) {
        jdbc.sql("""
                INSERT INTO broker_session (id, broker, broker_user_id, access_token_enc, public_token, established_at, expires_at,
                    status, detail, last_checked_at, updated_at)
                VALUES (:id, :broker, :userId, :token, :publicToken, :establishedAt, :expiresAt, 'CONNECTED', 'session established',
                    :now, :now)
                ON CONFLICT (broker) DO UPDATE SET broker_user_id = EXCLUDED.broker_user_id, access_token_enc = EXCLUDED.access_token_enc,
                    public_token = EXCLUDED.public_token, established_at = EXCLUDED.established_at, expires_at = EXCLUDED.expires_at,
                    status = 'CONNECTED', detail = EXCLUDED.detail, last_checked_at = EXCLUDED.last_checked_at, updated_at = EXCLUDED.updated_at
                """)
                .param("id", Ids.newId()).param("broker", broker).param("userId", brokerUserId).param("token", accessTokenEnc)
                .param("publicToken", publicToken).param("establishedAt", ts(establishedAt)).param("expiresAt", ts(expiresAt))
                .param("now", ts(now)).update();
    }

    /** Moves the row to a non-connected state and drops the stored token. */
    public void transition(String broker, BrokerSessionState status, String detail, Instant now) {
        jdbc.sql("""
                INSERT INTO broker_session (id, broker, status, detail, updated_at) VALUES (:id, :broker, :status, :detail, :now)
                ON CONFLICT (broker) DO UPDATE SET status = EXCLUDED.status, detail = EXCLUDED.detail, access_token_enc = NULL,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", Ids.newId()).param("broker", broker).param("status", status.name()).param("detail", detail)
                .param("now", ts(now)).update();
    }

    public void touchChecked(String broker, Instant now) {
        jdbc.sql("UPDATE broker_session SET last_checked_at = :now, updated_at = :now WHERE broker = :broker")
                .param("now", ts(now)).param("broker", broker).update();
    }

    private Row map(ResultSet rs, int i) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("broker"),
                rs.getString("broker_user_id"),
                rs.getString("access_token_enc"),
                rs.getString("public_token"),
                instant(rs.getObject("established_at", OffsetDateTime.class)),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                BrokerSessionState.valueOf(rs.getString("status")),
                rs.getString("detail"),
                instant(rs.getObject("last_checked_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
