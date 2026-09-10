package money.hejje.notify.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Ids;
import money.hejje.notify.Notification;
import money.hejje.notify.NotificationRule;
import money.hejje.notify.NotificationType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    NotificationStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Notification n) {
        jdbc.sql("""
                INSERT INTO notification (id, type, severity, title, body, data, dedupe_key, created_at)
                VALUES (:id, :type, :severity, :title, :body, CAST(:data AS jsonb), :dedupe, :at)
                """).param("id", n.id()).param("type", n.type().name()).param("severity", n.severity().name()).param("title", n.title())
                .param("body", n.body()).param("data", write(n.data())).param("dedupe", n.dedupeKey()).param("at", ts(n.createdAt())).update();
    }

    public boolean recent(String dedupeKey, Instant since) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE dedupe_key = :k AND created_at >= :since").param("k", dedupeKey).param("since", ts(since))
                .query(Integer.class).single() > 0;
    }

    public List<Notification> inbox(int limit) {
        return jdbc.sql("SELECT * FROM notification ORDER BY created_at DESC, id DESC LIMIT :limit").param("limit", limit).query(this::map).list();
    }

    public Optional<Notification> find(UUID id) {
        return jdbc.sql("SELECT * FROM notification WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public void markRead(UUID id, Instant at) {
        jdbc.sql("UPDATE notification SET read_at = COALESCE(read_at, :at) WHERE id = :id").param("at", ts(at)).param("id", id).update();
    }

    public List<NotificationRule> rules() {
        return jdbc.sql("SELECT * FROM notification_rule ORDER BY event_type, channel").query(this::mapRule).list();
    }

    public Optional<NotificationRule> rule(UUID id) {
        return jdbc.sql("SELECT * FROM notification_rule WHERE id = :id").param("id", id).query(this::mapRule).optional();
    }

    public void updateRule(UUID id, boolean enabled, NotificationType.Severity minSeverity, String by, Instant at) {
        jdbc.sql("UPDATE notification_rule SET enabled = :enabled, min_severity = :min, updated_by = :by, updated_at = :at WHERE id = :id")
                .param("enabled", enabled).param("min", minSeverity.name()).param("by", by).param("at", ts(at)).param("id", id).update();
    }

    public UUID delivery(UUID notificationId, NotificationType.Channel channel, String status, String detail, Instant at) {
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO notification_delivery (id, notification_id, channel, status, detail, created_at, sent_at) VALUES (:id, :n, :c, :s, :d, :at, :sent)")
                .param("id", id).param("n", notificationId).param("c", channel.name()).param("s", status).param("d", detail).param("at", ts(at))
                .param("sent", "SENT".equals(status) ? ts(at) : null).update();
        return id;
    }

    public void updateDelivery(UUID id, String status, String detail, Instant at) {
        jdbc.sql("UPDATE notification_delivery SET status = :s, detail = :d, sent_at = CASE WHEN :s IN ('SENT', 'DIGEST_SENT') THEN :at ELSE sent_at END WHERE id = :id")
                .param("s", status).param("d", detail).param("at", ts(at)).param("id", id).update();
    }

    public int sentSince(NotificationType.Channel channel, Instant since) {
        return jdbc.sql("SELECT count(*) FROM notification_delivery WHERE channel = :c AND status IN ('SENT', 'QUEUED') AND created_at >= :since")
                .param("c", channel.name()).param("since", ts(since)).query(Integer.class).single();
    }

    /** Held deliveries per channel with their notifications, oldest first. */
    public List<Map.Entry<UUID, Notification>> held(NotificationType.Channel channel) {
        return jdbc.sql("""
                SELECT d.id AS delivery_id, n.* FROM notification_delivery d JOIN notification n ON n.id = d.notification_id
                WHERE d.channel = :c AND d.status = 'DIGESTED' ORDER BY d.created_at
                """).param("c", channel.name()).query((rs, i) -> Map.entry(rs.getObject("delivery_id", UUID.class), map(rs, i))).list();
    }

    public List<Map<String, Object>> deliveries(UUID notificationId) {
        return jdbc.sql("SELECT channel, status, detail, created_at, sent_at FROM notification_delivery WHERE notification_id = :n ORDER BY created_at, channel")
                .param("n", notificationId).query((rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("channel", rs.getString("channel"));
                    m.put("status", rs.getString("status"));
                    if (rs.getString("detail") != null) {
                        m.put("detail", rs.getString("detail"));
                    }
                    m.put("createdAt", instant(rs, "created_at"));
                    if (rs.getObject("sent_at") != null) {
                        m.put("sentAt", instant(rs, "sent_at"));
                    }
                    return m;
                }).list();
    }

    private Notification map(ResultSet rs, int i) throws SQLException {
        return new Notification(rs.getObject("id", UUID.class), NotificationType.valueOf(rs.getString("type")),
                NotificationType.Severity.valueOf(rs.getString("severity")), rs.getString("title"), rs.getString("body"), read(rs.getString("data")),
                rs.getString("dedupe_key"), instant(rs, "created_at"), instant(rs, "read_at"));
    }

    private NotificationRule mapRule(ResultSet rs, int i) throws SQLException {
        return new NotificationRule(rs.getObject("id", UUID.class), NotificationType.valueOf(rs.getString("event_type")),
                NotificationType.Channel.valueOf(rs.getString("channel")), NotificationType.Severity.valueOf(rs.getString("min_severity")), rs.getBoolean("enabled"),
                instant(rs, "updated_at"), rs.getString("updated_by"));
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Map<String, Object> read(String text) {
        try {
            return json.readValue(text, MAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(ResultSet rs, String c) throws SQLException {
        OffsetDateTime v = rs.getObject(c, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
