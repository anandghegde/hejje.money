package money.hejje.execution.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.time.HejjeClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/** 24-hour idempotency records for transactional requests (PRD section 37). */
@Repository
public class IdempotencyStore {

    public record Record(UUID clientId, String key, String requestHash, Integer responseStatus, String responseBody) {}

    private final JdbcClient jdbc;
    private final HejjeClock clock;
    private final ObjectMapper json;

    IdempotencyStore(JdbcClient jdbc, HejjeClock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
    }

    /** Inserts a fresh in-flight record; returns true when this call owns it (no prior record for the key). */
    public boolean begin(UUID clientId, String key, String requestHash) {
        int rows = jdbc.sql("""
                INSERT INTO idempotency_record (client_id, key, request_hash, created_at) VALUES (:clientId, :key, :hash, :now)
                ON CONFLICT (client_id, key) DO NOTHING
                """)
                .param("clientId", clientId).param("key", key).param("hash", requestHash)
                .param("now", clock.now().atOffset(ZoneOffset.UTC)).update();
        return rows > 0;
    }

    public Optional<Record> find(UUID clientId, String key) {
        return jdbc.sql("SELECT client_id, key, request_hash, response_status, response_body FROM idempotency_record WHERE client_id = :clientId AND key = :key")
                .param("clientId", clientId).param("key", key)
                .query((rs, i) -> new Record(rs.getObject("client_id", UUID.class), rs.getString("key"), rs.getString("request_hash"),
                        rs.getObject("response_status") == null ? null : rs.getInt("response_status"), rs.getString("response_body")))
                .optional();
    }

    public void complete(UUID clientId, String key, int status, Map<String, Object> body) {
        jdbc.sql("UPDATE idempotency_record SET response_status = :status, response_body = CAST(:body AS jsonb) WHERE client_id = :clientId AND key = :key")
                .param("status", status).param("body", write(body)).param("clientId", clientId).param("key", key).update();
    }

    /** Removes an in-flight record that failed before completing, so the key can be retried. */
    public void abandon(UUID clientId, String key) {
        jdbc.sql("DELETE FROM idempotency_record WHERE client_id = :clientId AND key = :key AND response_status IS NULL")
                .param("clientId", clientId).param("key", key).update();
    }

    @Scheduled(cron = "0 0 * * * *")
    void cleanup() {
        jdbc.sql("DELETE FROM idempotency_record WHERE created_at < :cutoff")
                .param("cutoff", clock.now().minusSeconds(86400).atOffset(ZoneOffset.UTC)).update();
    }

    public Map<String, Object> readBody(String body) {
        try {
            return body == null ? Map.of() : json.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String write(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            return "{}";
        }
    }
}
