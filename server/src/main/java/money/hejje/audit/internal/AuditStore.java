package money.hejje.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditPage;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditRecord;
import money.hejje.common.ActorType;
import money.hejje.common.CorrelationId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AuditStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(AuditRecord r) {
        jdbc.sql("""
                INSERT INTO audit_event (id, ts, type, actor_type, actor_id, correlation_id, strategy_id, signal_id,
                    order_intent_id, order_id, broker_ref, client_source, payload)
                VALUES (:id, :ts, :type, :actorType, :actorId, :correlationId, :strategyId, :signalId,
                    :orderIntentId, :orderId, :brokerRef, :clientSource, CAST(:payload AS jsonb))
                """)
                .param("id", r.id())
                .param("ts", r.ts().atOffset(java.time.ZoneOffset.UTC))
                .param("type", r.type().name())
                .param("actorType", r.actorType().name())
                .param("actorId", r.actorId())
                .param("correlationId", r.correlationId().value())
                .param("strategyId", r.strategyId())
                .param("signalId", r.signalId())
                .param("orderIntentId", r.orderIntentId())
                .param("orderId", r.orderId())
                .param("brokerRef", r.brokerRef())
                .param("clientSource", r.clientSource())
                .param("payload", toJson(r.payload()))
                .update();
    }

    public AuditPage find(AuditQuery q) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q.from() != null) {
            where.append(" AND ts >= ?");
            args.add(q.from().atOffset(java.time.ZoneOffset.UTC));
        }
        if (q.to() != null) {
            where.append(" AND ts < ?");
            args.add(q.to().atOffset(java.time.ZoneOffset.UTC));
        }
        if (q.type() != null) {
            where.append(" AND type = ?");
            args.add(q.type().name());
        }
        if (q.orderId() != null) {
            where.append(" AND order_id = ?");
            args.add(q.orderId());
        }
        long total = jdbc.sql("SELECT count(*) FROM audit_event" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(q.size());
        pageArgs.add((long) q.page() * q.size());
        List<AuditRecord> rows = jdbc.sql("SELECT * FROM audit_event" + where + " ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?")
                .params(pageArgs)
                .query(this::map)
                .list();
        return new AuditPage(rows, q.page(), q.size(), total);
    }

    private AuditRecord map(ResultSet rs, int i) throws SQLException {
        return new AuditRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("ts", OffsetDateTime.class).toInstant(),
                AuditEventType.valueOf(rs.getString("type")),
                ActorType.valueOf(rs.getString("actor_type")),
                rs.getString("actor_id"),
                CorrelationId.of(rs.getObject("correlation_id", UUID.class)),
                rs.getObject("strategy_id", UUID.class),
                rs.getObject("signal_id", UUID.class),
                rs.getObject("order_intent_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("broker_ref"),
                rs.getString("client_source"),
                fromJson(rs.getString("payload")));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit payload is not serializable", e);
        }
    }

    private Map<String, Object> fromJson(String text) {
        try {
            return text == null ? Map.of() : json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored audit payload is not valid JSON", e);
        }
    }
}
