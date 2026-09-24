package money.hejje.llm.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.llm.JevAnswer;
import money.hejje.llm.JevCall;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code jev_call}, {@code jev_answer} and {@code jev_state} (V43). */
@Repository
public class JevStore {

    /** Today's usage: calls by outcome, tokens, estimated cost and latency percentiles of answered calls. */
    public record Usage(long calls, long ok, long cached, long failed, long timeouts, long inputTokens, BigDecimal costPaise, Long p50Ms, Long p90Ms) {
    }

    /** A stored answer set, reused by the SIM cache. */
    public record Cached(String model, Integer inputTokens, Map<String, JevAnswer> answers) {
    }

    private static final TypeReference<Map<String, Double>> PROBABILITIES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JevStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void insert(JevCall c, JsonNode state) {
        jdbc.sql("""
                INSERT INTO jev_call (id, at, purpose, subject, set_name, set_version, model, state_hash, latency_ms, input_tokens, cost_paise, outcome, error,
                                      correlation_id)
                VALUES (:id, :at, :purpose, :subject, :set, :version, :model, :hash, :latency, :tokens, :cost, :outcome, :error, :correlation)
                """)
                .param("id", c.id()).param("at", c.at().atOffset(ZoneOffset.UTC)).param("purpose", c.purpose()).param("subject", c.subject())
                .param("set", c.setName()).param("version", c.setVersion()).param("model", c.model()).param("hash", c.stateHash())
                .param("latency", c.latencyMs()).param("tokens", c.inputTokens(), java.sql.Types.INTEGER).param("cost", c.costPaise(), java.sql.Types.NUMERIC)
                .param("outcome", c.outcome()).param("error", c.error())
                .param("correlation", money.hejje.common.CorrelationContext.get().map(Object::toString).orElse(null))
                .update();
        for (JevAnswer a : c.answers()) {
            jdbc.sql("""
                    INSERT INTO jev_answer (call_id, key, type, choice, score, noul, probabilities, confidence)
                    VALUES (:call, :key, :type, :choice, :score, :noul, CAST(:probabilities AS jsonb), :confidence)
                    """)
                    .param("call", c.id()).param("key", a.key()).param("type", a.type()).param("choice", a.choice())
                    .param("score", a.score(), java.sql.Types.DOUBLE).param("noul", a.noul(), java.sql.Types.DOUBLE)
                    .param("probabilities", a.probabilities().isEmpty() ? null : write(a.probabilities()))
                    .param("confidence", a.confidence(), java.sql.Types.DOUBLE)
                    .update();
        }
        if (state != null) {
            jdbc.sql("INSERT INTO jev_state (call_id, state) VALUES (:call, CAST(:state AS jsonb))").param("call", c.id()).param("state", write(state)).update();
        }
    }

    /** The newest answered call for the same state and question set version. */
    public Optional<Cached> findCached(String stateHash, String setName, String setVersion) {
        Optional<Map<String, Object>> call = jdbc.sql("""
                SELECT id, model, input_tokens FROM jev_call
                WHERE state_hash = :hash AND set_name = :set AND set_version = :version AND outcome = 'OK'
                ORDER BY at DESC LIMIT 1
                """).param("hash", stateHash).param("set", setName).param("version", setVersion).query().listOfRows().stream().findFirst();
        return call.map(row -> {
            Map<String, JevAnswer> answers = new LinkedHashMap<>();
            answers((UUID) row.get("id")).forEach(a -> answers.put(a.key(), a));
            Object tokens = row.get("input_tokens");
            return new Cached((String) row.get("model"), tokens == null ? null : ((Number) tokens).intValue(), answers);
        });
    }

    public BigDecimal costSince(Instant from) {
        return jdbc.sql("SELECT COALESCE(SUM(cost_paise), 0) FROM jev_call WHERE at >= :from")
                .param("from", from.atOffset(ZoneOffset.UTC)).query(BigDecimal.class).single();
    }

    public Usage usageSince(Instant from) {
        return jdbc.sql("""
                SELECT COUNT(*) AS calls,
                       COUNT(*) FILTER (WHERE outcome = 'OK') AS ok,
                       COUNT(*) FILTER (WHERE outcome = 'CACHED') AS cached,
                       COUNT(*) FILTER (WHERE outcome NOT IN ('OK', 'CACHED', 'TIMEOUT')) AS failed,
                       COUNT(*) FILTER (WHERE outcome = 'TIMEOUT') AS timeouts,
                       COALESCE(SUM(input_tokens) FILTER (WHERE outcome = 'OK'), 0) AS tokens,
                       COALESCE(SUM(cost_paise), 0) AS cost,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE outcome = 'OK') AS p50,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE outcome = 'OK') AS p90
                FROM jev_call WHERE at >= :from
                """).param("from", from.atOffset(ZoneOffset.UTC))
                .query((rs, i) -> new Usage(rs.getLong("calls"), rs.getLong("ok"), rs.getLong("cached"), rs.getLong("failed"), rs.getLong("timeouts"),
                        rs.getLong("tokens"), rs.getBigDecimal("cost"), roundedOrNull(rs, "p50"), roundedOrNull(rs, "p90")))
                .single();
    }

    public List<JevCall> recent(String purpose, String subject, int limit) {
        List<JevCall> calls = jdbc.sql("""
                SELECT * FROM jev_call
                WHERE (CAST(:purpose AS text) IS NULL OR purpose = :purpose) AND (CAST(:subject AS text) IS NULL OR subject = :subject)
                ORDER BY at DESC LIMIT :n
                """).param("purpose", purpose).param("subject", subject).param("n", limit).query(this::map).list();
        List<JevCall> out = new ArrayList<>(calls.size());
        for (JevCall c : calls) {
            out.add(new JevCall(c.id(), c.at(), c.purpose(), c.subject(), c.setName(), c.setVersion(), c.model(), c.stateHash(), c.latencyMs(),
                    c.inputTokens(), c.costPaise(), c.outcome(), c.error(), answers(c.id())));
        }
        return out;
    }

    /** Deletes stored states of calls before {@code before}; the answers stay. Returns the number deleted. */
    public int pruneStates(Instant before) {
        return jdbc.sql("DELETE FROM jev_state s USING jev_call c WHERE s.call_id = c.id AND c.at < :before")
                .param("before", before.atOffset(ZoneOffset.UTC)).update();
    }

    private List<JevAnswer> answers(UUID callId) {
        return jdbc.sql("SELECT * FROM jev_answer WHERE call_id = :id ORDER BY key").param("id", callId).query((rs, i) -> new JevAnswer(
                rs.getString("key"), rs.getString("type"), rs.getString("choice"), doubleOrNull(rs, "score"), doubleOrNull(rs, "noul"),
                readProbabilities(rs.getString("probabilities")), doubleOrNull(rs, "confidence"))).list();
    }

    private JevCall map(ResultSet rs, int i) throws SQLException {
        Object tokens = rs.getObject("input_tokens");
        return new JevCall(rs.getObject("id", UUID.class), rs.getObject("at", OffsetDateTime.class).toInstant(), rs.getString("purpose"),
                rs.getString("subject"), rs.getString("set_name"), rs.getString("set_version"), rs.getString("model"), rs.getString("state_hash"),
                rs.getLong("latency_ms"), tokens == null ? null : ((Number) tokens).intValue(), rs.getBigDecimal("cost_paise"), rs.getString("outcome"),
                rs.getString("error"), List.of());
    }

    private Map<String, Double> readProbabilities(String s) {
        if (s == null) {
            return Map.of();
        }
        try {
            return json.readValue(s, PROBABILITIES);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static Long roundedOrNull(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : Math.round(v);
    }
}
