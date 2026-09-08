package money.hejje.execution.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import money.hejje.risk.RiskDecision;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persists every risk evaluation against an intent (populated in M1.4, enriched with the snapshot in M1.5). */
@Repository
public class RiskDecisionStore {

    private final JdbcClient jdbc;
    private final HejjeClock clock;
    private final ObjectMapper json;

    RiskDecisionStore(JdbcClient jdbc, HejjeClock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
    }

    public void save(UUID intentId, RiskDecision decision, Map<String, Object> snapshot) {
        jdbc.sql("""
                INSERT INTO risk_decision (id, intent_id, outcome, checks, snapshot, created_at)
                VALUES (:id, :intentId, :outcome, CAST(:checks AS jsonb), CAST(:snapshot AS jsonb), :now)
                """)
                .param("id", Ids.newId()).param("intentId", intentId).param("outcome", decision.outcome().name())
                .param("checks", write(decision.checks())).param("snapshot", write(snapshot))
                .param("now", clock.now().atOffset(java.time.ZoneOffset.UTC)).update();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
