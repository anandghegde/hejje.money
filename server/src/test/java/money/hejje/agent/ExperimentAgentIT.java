package money.hejje.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.llm.FixtureLlmProvider;
import money.hejje.llm.LlmRequest;
import money.hejje.llm.LlmService;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** propose_variants with the fixture LLM produces validated deltas; run_experiment and get_experiment work through the tool API (plan M4.7). */
@SuppressWarnings({"unchecked", "rawtypes"})
class ExperimentAgentIT extends AbstractIntegrationTest {

    static final String PROPOSAL = """
            {"variants": [
              {"name": "vwap_filter", "rationale": "Breakouts below VWAP fail more often", "delta": {"entry_add": ["close > vwap"]}},
              {"name": "wider_target", "rationale": "Winners run further in trending sessions", "delta": {"target": {"value": 3}}},
              {"name": "made_up", "rationale": "An indicator Hejje does not have", "delta": {"entry_add": ["ichimoku_base(26) > close"]}}
            ]}""";

    @Autowired LlmService llm;
    @Autowired StrategyService strategies;

    FixtureLlmProvider fixture;

    @BeforeEach
    void setUp() {
        fixture = (FixtureLlmProvider) llm.provider("fixture").orElseThrow();
        fixture.reset();
        fixture.respondWhenContains("Propose the variants now.", PROPOSAL);
    }

    @AfterEach
    void tearDown() {
        fixture.reset();
    }

    ResponseEntity<Map> tool(String token, String name, Map<String, Object> body) {
        return rest.exchange("/api/v1/agents/tools/" + name, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), Map.class);
    }

    @Test
    void proposedDeltasAreValidatedAndCanBeRun() throws Exception {
        String admin = adminAccessToken();
        StrategyVersion base = strategies.create(money.hejje.backtest.experiments.ExperimentITYaml.yaml("exp_agent_" + UUID.randomUUID().toString().substring(0, 6)), null,
                "admin");
        ResponseEntity<Map> proposed = tool(admin, "propose_variants", Map.of("version", base.id().toString(), "goal", "fewer false breakouts"));
        assertThat(proposed.getStatusCode().value()).as("%s", proposed.getBody()).isEqualTo(200);
        List<Map<String, Object>> variants = (List<Map<String, Object>>) ((Map<String, Object>) proposed.getBody().get("output")).get("variants");
        assertThat(variants).extracting(v -> v.get("name"), v -> v.get("valid"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vwap_filter", true), org.assertj.core.groups.Tuple.tuple("wider_target", true),
                        org.assertj.core.groups.Tuple.tuple("made_up", false));
        assertThat((List<String>) variants.get(0).get("entryConditions")).contains("close > vwap");
        assertThat((List<String>) variants.get(2).get("errors")).anyMatch(e -> e.contains("ichimoku_base"));

        LlmRequest request = fixture.requests().get(0);
        assertThat(request.profile()).isEqualTo("research");
        assertThat(request.promptVersion()).isEqualTo("experiment_agent_v1");
        assertThat(request.systemPrompt()).contains("out-of-sample improvement, robustness").contains("fewer false breakouts").contains("name: exp_agent_")
                .contains("No backtest yet.");

        List<Map<String, Object>> valid = variants.stream().filter(v -> Boolean.TRUE.equals(v.get("valid")))
                .map(v -> Map.<String, Object>of("name", v.get("name"), "delta", v.get("delta"))).toList();
        ResponseEntity<Map> run = tool(admin, "run_experiment", Map.of("version", base.id().toString(), "goal", "fewer false breakouts", "variants", valid,
                "from", "2025-12-01", "to", "2025-12-05", "splits", "NONE"));
        assertThat(run.getStatusCode().value()).as("%s", run.getBody()).isEqualTo(200);
        String experimentId = (String) ((Map<String, Object>) run.getBody().get("output")).get("id");
        Map<String, Object> got = null;
        for (int i = 0; i < 300; i++) {
            got = (Map<String, Object>) tool(admin, "get_experiment", Map.of("experimentId", experimentId)).getBody().get("output");
            if (!"QUEUED".equals(got.get("status")) && !"RUNNING".equals(got.get("status"))) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(got).containsEntry("status", "DONE").containsEntry("goal", "fewer false breakouts");
        assertThat((List<?>) got.get("variants")).hasSize(3);
    }
}
