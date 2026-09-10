package money.hejje.risk.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({"unchecked", "rawtypes"})
class PolicyIT extends AbstractIntegrationTest {

    @Autowired
    ClientCredentialService clients;

    @Autowired
    JdbcTemplate jdbc;

    ResponseEntity<Map> put(String token, String id, Map<String, Object> body) {
        return rest.exchange("/api/v1/risk/policies/" + id, HttpMethod.PUT, new HttpEntity<>(body, bearer(token)), Map.class);
    }

    @Test
    void seededPoliciesAreInspectableAndEditsNeedRiskWrite() {
        String admin = adminAccessToken();
        Map<String, Object> view = rest.exchange("/api/v1/risk/policies", HttpMethod.GET, new HttpEntity<>(bearer(admin)), Map.class).getBody();
        List<Map<String, Object>> rules = (List<Map<String, Object>>) view.get("rules");
        assertThat(rules).extracting(r -> r.get("name")).containsExactly("daily_loss_block", "autonomy_above_phase", "agent_needs_prepare_level", "event_risk_high",
                "new_strategy_version", "agent_actions", "manual_orders", "score_below_80", "strategy_signals");
        assertThat(view).containsEntry("defaultDecision", "REQUIRE_APPROVAL");
        assertThat(rules.get(0)).containsEntry("decision", "DENY").containsEntry("condition", "DAILY_LOSS_EXCEEDED");
        assertThat((List<String>) rules.get(0).get("actions")).containsExactly("ORDER_NEW");

        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String reader = clients.create("policy-reader-" + UUID.randomUUID(), Set.of("risk:read"), null, actor).key();
        assertThat(rest.exchange("/api/v1/risk/policies", HttpMethod.GET, new HttpEntity<>(bearer(reader)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = (String) rules.stream().filter(r -> r.get("name").equals("score_below_80")).findFirst().orElseThrow().get("id");
        assertThat(put(reader, id, Map.of("enabled", false)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // edits use their own risk:write key: /api/v1/risk is a transactional path whose per-principal bucket (burst 5 in tests) the admin shares with other ITs
        String writerName = "policy-writer-" + UUID.randomUUID();
        String writer = clients.create(writerName, Set.of("risk:read", "risk:write"), null, actor).key();
        ResponseEntity<Map> disabled = put(writer, id, Map.of("enabled", false));
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody()).containsEntry("enabled", false).containsEntry("updatedBy", writerName);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'POLICY_UPDATED' AND payload->>'rule' = 'score_below_80'", Long.class))
                .isPositive();
        assertThat(put(writer, id, Map.of("decision", "ALLOW")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(writer, id, Map.of("enabled", true)).getBody()).containsEntry("enabled", true);
        assertThat(put(writer, UUID.randomUUID().toString(), Map.of("enabled", true)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
