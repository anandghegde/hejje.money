package money.hejje.risk.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import money.hejje.risk.policy.PolicyAction;
import money.hejje.risk.policy.PolicyCondition;
import money.hejje.risk.policy.PolicyDecision;
import money.hejje.risk.policy.PolicyRule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyRuleStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    PolicyRuleStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<PolicyRule> findAll() {
        return jdbc.sql("SELECT * FROM policy_rule ORDER BY priority, name").query(this::map).list();
    }

    public Optional<PolicyRule> find(UUID id) {
        return jdbc.sql("SELECT * FROM policy_rule WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public void update(PolicyRule r) {
        try {
            jdbc.sql("""
                    UPDATE policy_rule SET priority = :priority, decision = :decision, params = CAST(:params AS jsonb), enabled = :enabled,
                           updated_at = :at, updated_by = :by WHERE id = :id
                    """).param("id", r.id()).param("priority", r.priority()).param("decision", r.decision().name())
                    .param("params", json.writeValueAsString(r.params())).param("enabled", r.enabled()).param("at", r.updatedAt().atOffset(ZoneOffset.UTC))
                    .param("by", r.updatedBy()).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid params", e);
        }
    }

    private PolicyRule map(ResultSet rs, int i) throws SQLException {
        try {
            List<String> actions = json.readValue(rs.getString("actions"), new TypeReference<List<String>>() {});
            Set<PolicyAction> set = new LinkedHashSet<>();
            actions.forEach(a -> set.add(PolicyAction.valueOf(a)));
            Map<String, Object> params = json.readValue(rs.getString("params"), new TypeReference<LinkedHashMap<String, Object>>() {});
            return new PolicyRule(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("priority"), PolicyCondition.valueOf(rs.getString("condition")),
                    set, PolicyDecision.valueOf(rs.getString("decision")), params, rs.getBoolean("enabled"), rs.getString("description"),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getString("updated_by"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Unreadable policy_rule row", e);
        }
    }
}
