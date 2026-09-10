package money.hejje.agent.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.agent.AiConversation;
import money.hejje.agent.AiMessage;
import money.hejje.agent.AiTraceStep;
import money.hejje.agent.Grounding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ConversationStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertConversation(AiConversation c) {
        jdbc.sql("""
                INSERT INTO agent_conversation (id, session_id, principal_id, title, created_at, updated_at)
                VALUES (:id, :session, :principal, :title, :created, :updated)
                """).param("id", c.id()).param("session", c.sessionId()).param("principal", c.principalId()).param("title", c.title())
                .param("created", utc(c.createdAt())).param("updated", utc(c.updatedAt())).update();
    }

    public void touch(UUID id, Instant at) {
        jdbc.sql("UPDATE agent_conversation SET updated_at = :at WHERE id = :id").param("id", id).param("at", utc(at)).update();
    }

    public Optional<AiConversation> find(UUID id) {
        return jdbc.sql("SELECT * FROM agent_conversation WHERE id = :id").param("id", id).query(this::conversation).optional();
    }

    public List<AiConversation> conversations(UUID principalId, int limit) {
        return jdbc.sql("SELECT * FROM agent_conversation WHERE principal_id = :p ORDER BY updated_at DESC LIMIT :n").param("p", principalId).param("n", limit)
                .query(this::conversation).list();
    }

    public void insertMessage(AiMessage m) {
        jdbc.sql("""
                INSERT INTO agent_message (id, conversation_id, seq, role, content, flow, profile, grounding, trace, steps, created_at)
                VALUES (:id, :conversation, :seq, :role, :content, :flow, :profile, CAST(:grounding AS jsonb), CAST(:trace AS jsonb), :steps, :created)
                """).param("id", m.id()).param("conversation", m.conversationId()).param("seq", m.seq()).param("role", m.role()).param("content", m.content())
                .param("flow", m.flow()).param("profile", m.profile()).param("grounding", write(m.grounding())).param("trace", write(m.trace()))
                .param("steps", m.steps(), java.sql.Types.INTEGER).param("created", utc(m.createdAt())).update();
    }

    public List<AiMessage> messages(UUID conversationId) {
        return jdbc.sql("SELECT * FROM agent_message WHERE conversation_id = :c ORDER BY seq").param("c", conversationId).query(this::message).list();
    }

    private AiConversation conversation(ResultSet rs, int i) throws SQLException {
        return new AiConversation(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class), rs.getObject("principal_id", UUID.class),
                rs.getString("title"), rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private AiMessage message(ResultSet rs, int i) throws SQLException {
        Object steps = rs.getObject("steps");
        return new AiMessage(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class), rs.getInt("seq"), rs.getString("role"),
                rs.getString("content"), rs.getString("flow"), rs.getString("profile"), read(rs.getString("grounding"), new TypeReference<Grounding>() {}),
                read(rs.getString("trace"), new TypeReference<List<AiTraceStep>>() {}), steps == null ? null : ((Number) steps).intValue(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private String write(Object value) {
        try {
            return value == null ? null : json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return text == null ? null : json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
