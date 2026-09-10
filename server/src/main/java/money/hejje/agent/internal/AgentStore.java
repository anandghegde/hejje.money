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
import money.hejje.agent.AgentAction;
import money.hejje.agent.AgentSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AgentStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AgentStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertSession(AgentSession s) {
        jdbc.sql("""
                INSERT INTO agent_session (id, client_credential_id, principal_type, principal_id, principal_name, profile, purpose, started_at, ended_at)
                VALUES (:id, :client, :type, :principalId, :name, :profile, :purpose, :started, NULL)
                """)
                .param("id", s.id()).param("client", s.clientCredentialId()).param("type", s.principalType()).param("principalId", s.principalId())
                .param("name", s.principalName()).param("profile", s.profile()).param("purpose", s.purpose()).param("started", utc(s.startedAt())).update();
    }

    public void endSession(UUID id, Instant at) {
        jdbc.sql("UPDATE agent_session SET ended_at = :at WHERE id = :id AND ended_at IS NULL").param("id", id).param("at", utc(at)).update();
    }

    public Optional<AgentSession> findSession(UUID id) {
        return jdbc.sql("SELECT * FROM agent_session WHERE id = :id").param("id", id).query(this::session).optional();
    }

    public Optional<AgentSession> openSession(UUID principalId, String purpose, Instant since) {
        return jdbc.sql("""
                SELECT * FROM agent_session WHERE principal_id = :p AND purpose = :purpose AND ended_at IS NULL AND started_at >= :since
                ORDER BY started_at DESC LIMIT 1
                """).param("p", principalId).param("purpose", purpose).param("since", utc(since)).query(this::session).optional();
    }

    public List<AgentSession> sessions(UUID principalIdOrNull, int limit) {
        if (principalIdOrNull == null) {
            return jdbc.sql("SELECT * FROM agent_session ORDER BY started_at DESC LIMIT :n").param("n", limit).query(this::session).list();
        }
        return jdbc.sql("SELECT * FROM agent_session WHERE principal_id = :p ORDER BY started_at DESC LIMIT :n")
                .param("p", principalIdOrNull).param("n", limit).query(this::session).list();
    }

    public void insertAction(AgentAction a) {
        jdbc.sql("""
                INSERT INTO agent_action (id, session_id, tool, input, output_summary, scope_ok, status, error, latency_ms, correlation_id, ts)
                VALUES (:id, :session, :tool, CAST(:input AS jsonb), CAST(:output AS jsonb), :scopeOk, :status, :error, :latency, :correlation, :ts)
                """)
                .param("id", a.id()).param("session", a.sessionId()).param("tool", a.tool()).param("input", a.input().toString())
                .param("output", a.outputSummary() == null ? null : a.outputSummary().toString()).param("scopeOk", a.scopeOk()).param("status", a.status())
                .param("error", a.error()).param("latency", a.latencyMs()).param("correlation", a.correlationId()).param("ts", utc(a.ts())).update();
    }

    public List<AgentAction> actions(UUID sessionId) {
        return jdbc.sql("SELECT * FROM agent_action WHERE session_id = :s ORDER BY ts, id").param("s", sessionId).query(this::action).list();
    }

    private AgentSession session(ResultSet rs, int i) throws SQLException {
        OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
        return new AgentSession(rs.getObject("id", UUID.class), rs.getObject("client_credential_id", UUID.class), rs.getString("principal_type"),
                rs.getObject("principal_id", UUID.class), rs.getString("principal_name"), rs.getString("profile"), rs.getString("purpose"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(), ended == null ? null : ended.toInstant());
    }

    private AgentAction action(ResultSet rs, int i) throws SQLException {
        return new AgentAction(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class), rs.getString("tool"), tree(rs.getString("input")),
                tree(rs.getString("output_summary")), rs.getBoolean("scope_ok"), rs.getString("status"), rs.getString("error"), rs.getLong("latency_ms"),
                rs.getString("correlation_id"), rs.getObject("ts", OffsetDateTime.class).toInstant());
    }

    private JsonNode tree(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (Exception e) {
            return json.getNodeFactory().textNode(text);
        }
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
