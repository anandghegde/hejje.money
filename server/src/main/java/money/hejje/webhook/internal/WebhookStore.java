package money.hejje.webhook.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Ids;
import money.hejje.webhook.Webhook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookStore {

    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    WebhookStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Webhook w, String secretEnc) {
        jdbc.sql("""
                INSERT INTO webhook (id, name, secret_enc, auth_mode, strategy_version_id, enabled, allowed_instruments, created_at, created_by, updated_at)
                VALUES (:id, :name, :secret, :mode, :version, :enabled, CAST(:allowed AS jsonb), :at, :by, :at)
                """).param("id", w.id()).param("name", w.name()).param("secret", secretEnc).param("mode", w.authMode().name())
                .param("version", w.strategyVersionId()).param("enabled", w.enabled()).param("allowed", write(w.allowedInstruments()))
                .param("at", ts(w.createdAt())).param("by", w.createdBy()).update();
    }

    public Optional<Webhook> find(UUID id) {
        return jdbc.sql("SELECT * FROM webhook WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public boolean nameTaken(String name) {
        return jdbc.sql("SELECT count(*) FROM webhook WHERE name = :name").param("name", name).query(Integer.class).single() > 0;
    }

    public List<Webhook> list() {
        return jdbc.sql("SELECT * FROM webhook ORDER BY created_at").query(this::map).list();
    }

    public String secretEnc(UUID id) {
        return jdbc.sql("SELECT secret_enc FROM webhook WHERE id = :id").param("id", id).query(String.class).single();
    }

    public void update(UUID id, boolean enabled, List<String> allowed, Instant at) {
        jdbc.sql("UPDATE webhook SET enabled = :enabled, allowed_instruments = CAST(:allowed AS jsonb), updated_at = :at WHERE id = :id")
                .param("enabled", enabled).param("allowed", write(allowed)).param("at", ts(at)).param("id", id).update();
    }

    public void rotate(UUID id, String secretEnc, Instant at) {
        jdbc.sql("UPDATE webhook SET secret_enc = :secret, updated_at = :at WHERE id = :id").param("secret", secretEnc).param("at", ts(at)).param("id", id).update();
    }

    public void touch(UUID id, Instant at) {
        jdbc.sql("UPDATE webhook SET last_received_at = :at WHERE id = :id").param("at", ts(at)).param("id", id).update();
    }

    /** Logs a delivery; an ACCEPTED one claims its replay key (unique), so a concurrent replay fails here. */
    public UUID delivery(UUID webhookId, Instant at, String replayKey, String status, String detail, String payload) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO webhook_delivery (id, webhook_id, received_at, replay_key, status, detail, payload)
                VALUES (:id, :w, :at, :key, :status, :detail, CAST(:payload AS jsonb))
                """).param("id", id).param("w", webhookId).param("at", ts(at)).param("key", replayKey).param("status", status).param("detail", detail)
                .param("payload", payload).update();
        return id;
    }

    public void finish(UUID deliveryId, String status, String detail, UUID signalId, UUID approvalId) {
        jdbc.sql("UPDATE webhook_delivery SET status = :status, detail = :detail, signal_id = :signal, approval_id = :approval WHERE id = :id")
                .param("status", status).param("detail", detail).param("signal", signalId).param("approval", approvalId).param("id", deliveryId).update();
    }

    public boolean accepted(UUID webhookId, String replayKey) {
        return jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE webhook_id = :w AND replay_key = :key AND status = 'ACCEPTED'").param("w", webhookId)
                .param("key", replayKey).query(Integer.class).single() > 0;
    }

    public List<Map<String, Object>> deliveries(UUID webhookId, int limit) {
        return jdbc.sql("SELECT * FROM webhook_delivery WHERE webhook_id = :w ORDER BY received_at DESC, id DESC LIMIT :limit").param("w", webhookId)
                .param("limit", limit).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("receivedAt", rs.getObject("received_at", OffsetDateTime.class).toInstant());
                    m.put("status", rs.getString("status"));
                    m.put("detail", rs.getString("detail"));
                    m.put("signalId", rs.getObject("signal_id", UUID.class));
                    m.put("approvalId", rs.getObject("approval_id", UUID.class));
                    return m;
                }).list();
    }

    private Webhook map(ResultSet rs, int i) throws SQLException {
        OffsetDateTime last = rs.getObject("last_received_at", OffsetDateTime.class);
        return new Webhook(rs.getObject("id", UUID.class), rs.getString("name"), Webhook.AuthMode.valueOf(rs.getString("auth_mode")),
                rs.getObject("strategy_version_id", UUID.class), rs.getBoolean("enabled"), read(rs.getString("allowed_instruments")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("created_by"), rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                last == null ? null : last.toInstant());
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private List<String> read(String text) {
        try {
            return json.readValue(text, LIST);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
