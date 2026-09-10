package money.hejje.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.agent.Approval;
import money.hejje.agent.ApprovalKind;
import money.hejje.agent.ApprovalStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code approval} rows. Status changes are conditional updates, so two deciders can never both win. */
@Repository
public class ApprovalStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ApprovalStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Approval a) {
        jdbc.sql("""
                INSERT INTO approval (id, kind, status, mode, intent_id, signal_id, strategy_id, instrument_id, instrument, order_id, requested_by_session,
                                      requested_by, requested_by_principal, requested_by_type, request_key, summary, rationale, proposal, risk, policy,
                                      created_at, expires_at)
                VALUES (:id, :kind, :status, :mode, :intent, :signal, :strategy, :instrumentId, :instrument, :order, :session, :by, :principal, :type, :key,
                        :summary, :rationale, CAST(:proposal AS jsonb), CAST(:risk AS jsonb), CAST(:policy AS jsonb), :created, :expires)
                """)
                .param("id", a.id()).param("kind", a.kind().name()).param("status", a.status().name()).param("mode", a.mode()).param("intent", a.intentId())
                .param("signal", a.signalId()).param("strategy", a.strategyId()).param("instrumentId", a.instrumentId()).param("instrument", a.instrument())
                .param("order", a.orderId()).param("session", a.requestedBySession()).param("by", a.requestedBy()).param("principal", a.requestedByPrincipal())
                .param("type", a.requestedByType()).param("key", a.requestKey()).param("summary", a.summary()).param("rationale", a.rationale())
                .param("proposal", text(a.proposal())).param("risk", text(a.risk())).param("policy", text(a.policy())).param("created", utc(a.createdAt()))
                .param("expires", utc(a.expiresAt())).update();
    }

    public Optional<Approval> find(UUID id) {
        return jdbc.sql("SELECT * FROM approval WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<Approval> findByRequestKey(UUID principal, String key) {
        return jdbc.sql("SELECT * FROM approval WHERE requested_by_principal = :p AND request_key = :k").param("p", principal).param("k", key)
                .query(this::map).optional();
    }

    public List<Approval> list(ApprovalStatus status, int limit) {
        if (status == null) {
            return jdbc.sql("SELECT * FROM approval ORDER BY created_at DESC, id DESC LIMIT :n").param("n", limit).query(this::map).list();
        }
        return jdbc.sql("SELECT * FROM approval WHERE status = :s ORDER BY created_at DESC, id DESC LIMIT :n").param("s", status.name()).param("n", limit)
                .query(this::map).list();
    }

    /** PENDING → APPROVED; false when someone else decided first. */
    public boolean claim(UUID id, String by, Instant at, String key) {
        return jdbc.sql("""
                UPDATE approval SET status = 'APPROVED', decided_by = :by, decided_at = :at, decision_key = :key WHERE id = :id AND status = 'PENDING'
                """).param("id", id).param("by", by).param("at", utc(at)).param("key", key).update() == 1;
    }

    public void complete(UUID id, JsonNode result) {
        jdbc.sql("UPDATE approval SET result = CAST(:result AS jsonb) WHERE id = :id").param("id", id).param("result", text(result)).update();
    }

    /** PENDING or APPROVED (execution failed) → FAILED. */
    public boolean fail(UUID id, String by, Instant at, String note, JsonNode result) {
        return jdbc.sql("""
                UPDATE approval SET status = 'FAILED', decided_by = COALESCE(decided_by, :by), decided_at = COALESCE(decided_at, :at), decision_note = :note,
                       result = CAST(:result AS jsonb)
                WHERE id = :id AND status IN ('PENDING', 'APPROVED')
                """).param("id", id).param("by", by).param("at", utc(at)).param("note", note).param("result", text(result)).update() == 1;
    }

    public boolean reject(UUID id, String by, Instant at, String note, String key) {
        return jdbc.sql("""
                UPDATE approval SET status = 'REJECTED', decided_by = :by, decided_at = :at, decision_note = :note, decision_key = :key
                WHERE id = :id AND status = 'PENDING'
                """).param("id", id).param("by", by).param("at", utc(at)).param("note", note).param("key", key).update() == 1;
    }

    /** PENDING approvals past their expiry → EXPIRED, returned. */
    public List<Approval> expireDue(Instant now) {
        return jdbc.sql("""
                UPDATE approval SET status = 'EXPIRED', decided_at = :now, decision_note = 'expired at ' || to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                WHERE status = 'PENDING' AND expires_at <= :now RETURNING *
                """).param("now", utc(now)).query(this::map).list();
    }

    private Approval map(ResultSet rs, int i) throws SQLException {
        OffsetDateTime decided = rs.getObject("decided_at", OffsetDateTime.class);
        return new Approval(rs.getObject("id", UUID.class), ApprovalKind.valueOf(rs.getString("kind")), ApprovalStatus.valueOf(rs.getString("status")),
                rs.getString("mode"), rs.getObject("intent_id", UUID.class), rs.getObject("signal_id", UUID.class), rs.getObject("strategy_id", UUID.class),
                rs.getObject("instrument_id", UUID.class), rs.getString("instrument"), rs.getObject("order_id", UUID.class),
                rs.getObject("requested_by_session", UUID.class), rs.getString("requested_by"), rs.getObject("requested_by_principal", UUID.class),
                rs.getString("requested_by_type"), rs.getString("request_key"), rs.getString("summary"), rs.getString("rationale"), tree(rs.getString("proposal")),
                tree(rs.getString("risk")), tree(rs.getString("policy")), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("decided_by"), decided == null ? null : decided.toInstant(),
                rs.getString("decision_key"), rs.getString("decision_note"), tree(rs.getString("result")));
    }

    private JsonNode tree(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (Exception e) {
            return json.getNodeFactory().textNode(text);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
