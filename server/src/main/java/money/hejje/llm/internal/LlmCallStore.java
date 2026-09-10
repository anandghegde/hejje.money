package money.hejje.llm.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import money.hejje.llm.LlmCall;
import money.hejje.llm.LlmUsage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LlmCallStore {

    private final JdbcClient jdbc;

    LlmCallStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LlmCall c) {
        jdbc.sql("""
                INSERT INTO llm_call (id, at, profile, provider, model, purpose, prompt_version, prompt_hash, input_tokens, output_tokens, cost_estimate_paise,
                                      latency_ms, correlation_id, status, error)
                VALUES (:id, :at, :profile, :provider, :model, :purpose, :promptVersion, :promptHash, :in, :out, :cost, :latency, :correlationId, :status, :error)
                """)
                .param("id", c.id()).param("at", c.at().atOffset(ZoneOffset.UTC)).param("profile", c.profile()).param("provider", c.provider())
                .param("model", c.model()).param("purpose", c.purpose()).param("promptVersion", c.promptVersion()).param("promptHash", c.promptHash())
                .param("in", c.inputTokens(), java.sql.Types.INTEGER).param("out", c.outputTokens(), java.sql.Types.INTEGER)
                .param("cost", c.costEstimatePaise(), java.sql.Types.BIGINT).param("latency", c.latencyMs()).param("correlationId", c.correlationId())
                .param("status", c.status()).param("error", c.error()).update();
    }

    /** Estimated cost of every call since {@code from}, in paise (calls without pricing count as zero). */
    public long costSince(java.time.Instant from) {
        return jdbc.sql("SELECT COALESCE(SUM(cost_estimate_paise), 0) FROM llm_call WHERE at >= :from")
                .param("from", from.atOffset(ZoneOffset.UTC)).query(Long.class).single();
    }

    public LlmUsage usageSince(java.time.Instant from) {
        return jdbc.sql("""
                SELECT COUNT(*) AS calls, COUNT(*) FILTER (WHERE status <> 'OK') AS failed, COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens, COALESCE(SUM(cost_estimate_paise), 0) AS cost
                FROM llm_call WHERE at >= :from
                """).param("from", from.atOffset(ZoneOffset.UTC))
                .query((rs, i) -> new LlmUsage(rs.getLong("calls"), rs.getLong("failed"), rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getLong("cost")))
                .single();
    }

    public List<LlmCall> recent(int limit) {
        return jdbc.sql("SELECT * FROM llm_call ORDER BY at DESC LIMIT :n").param("n", limit).query(this::map).list();
    }

    private LlmCall map(ResultSet rs, int i) throws SQLException {
        Object in = rs.getObject("input_tokens");
        Object out = rs.getObject("output_tokens");
        Object cost = rs.getObject("cost_estimate_paise");
        return new LlmCall(rs.getObject("id", java.util.UUID.class), rs.getObject("at", OffsetDateTime.class).toInstant(), rs.getString("profile"),
                rs.getString("provider"), rs.getString("model"), rs.getString("purpose"), rs.getString("prompt_version"), rs.getString("prompt_hash"),
                in == null ? null : ((Number) in).intValue(), out == null ? null : ((Number) out).intValue(), cost == null ? null : ((Number) cost).longValue(),
                rs.getLong("latency_ms"), rs.getString("correlation_id"), rs.getString("status"), rs.getString("error"));
    }
}
