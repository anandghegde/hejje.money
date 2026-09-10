package money.hejje.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import money.hejje.AbstractIntegrationTest;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.common.security.AgentPresets;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Natural-language strategy builder with the fixture LLM (plan M4.6). */
@SuppressWarnings({"unchecked", "rawtypes"})
class StrategyBuilderIT extends AbstractIntegrationTest {

    static final String PRD_22 = "Buy NIFTY when price closes above the first 15-minute high, provided it is above VWAP and relative volume exceeds 1.5. "
            + "Stop below the opening range and target 2R.";

    static String prdYaml(String name) {
        return """
                Here is the definition:
                ```yaml
                name: %s
                family: index
                description: Long NIFTY when the close breaks the first 15-minute high above VWAP on 1.5x relative volume.
                universe:
                  - NIFTY
                timeframe: 5m
                direction: long
                entry:
                  all:
                    - close > opening_range_high(15m)
                    - close > vwap
                    - relative_volume > 1.5
                stop:
                  type: opening_range_low
                target:
                  type: risk_multiple
                  value: 2
                ```""".formatted(name);
    }

    static final String INVALID = """
            ```yaml
            name: nl_broken_draft
            universe:
              - NIFTY
            timeframe: 5m
            direction: long
            entry:
              all:
                - close > magic_line(3)
            stop:
              type: atr_multiple
            ```""";

    @Autowired LlmService llm;
    @Autowired StrategyService strategies;
    @Autowired ClientCredentialService clients;

    FixtureLlmProvider fixture;
    String admin;

    @BeforeEach
    void setUp() {
        fixture = (FixtureLlmProvider) llm.provider("fixture").orElseThrow();
        fixture.reset();
        admin = adminAccessToken();
    }

    @AfterEach
    void tearDown() {
        fixture.reset();
    }

    ResponseEntity<Map> draftTool(String token, Map<String, Object> body) {
        return rest.exchange("/api/v1/agents/tools/create_strategy_draft", HttpMethod.POST, new HttpEntity<>(body, bearer(token)), Map.class);
    }

    ResponseEntity<Map> draftRest(Map<String, Object> body) {
        return rest.exchange("/api/v1/strategies/drafts", HttpMethod.POST, new HttpEntity<>(body, bearer(admin)), Map.class);
    }

    @Test
    void thePrdSentenceBecomesAValidDraftWithTheExpectedRules() {
        String name = "nl_orb_" + UUID.randomUUID().toString().substring(0, 6);
        fixture.respondWhenContains("Buy NIFTY when price closes above the first 15-minute high", prdYaml(name));
        ResponseEntity<Map> r = draftTool(admin, Map.of("description", PRD_22));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> draft = (Map<String, Object>) r.getBody().get("output");
        assertThat(draft).containsEntry("created", true).containsEntry("slug", name).containsEntry("version", 1).containsEntry("status", "DRAFT")
                .containsEntry("changeNote", "NL draft");
        assertThat((List<String>) draft.get("rules")).contains("• the close is above the 15-minute opening-range high", "• the close is above VWAP",
                "• relative volume (20 sessions) is above 1.5", "Stop: below the opening-range low.", "Target: 2R (2 × the risk).");
        StrategyVersion version = strategies.versionById(UUID.fromString((String) draft.get("versionId"))).orElseThrow();
        assertThat(version.definition().entry().conditions()).extracting(Object::toString)
                .containsExactly("close > opening_range_high(15m)", "close > vwap", "relative_volume(20) > 1.5");
        assertThat(version.definition().stop().type().name()).isEqualTo("OPENING_RANGE_LOW");
        assertThat(version.definition().target().value()).isEqualByComparingTo("2");

        LlmRequest request = fixture.requests().get(0);
        assertThat(request.profile()).isEqualTo("reasoning");
        assertThat(request.temperature()).isEqualTo(0.0);
        assertThat(request.promptVersion()).isEqualTo("strategy_builder_v1");
        assertThat(request.systemPrompt()).contains("## Condition grammar").contains("name: nifty_orb").contains("name: vwap_reversion");

        // the same answer again: the name is taken, so the new strategy gets a suffix
        Map<String, Object> again = (Map<String, Object>) draftTool(admin, Map.of("description", PRD_22)).getBody().get("output");
        assertThat(again).containsEntry("slug", name + "_nl");
    }

    @Test
    void errorsAreFedBackAndFixedWithinThreeIterations() {
        String name = "nl_fixed_" + UUID.randomUUID().toString().substring(0, 6);
        AtomicInteger calls = new AtomicInteger();
        fixture.responder(r -> "strategy-builder".equals(r.purpose()) && r.userPrompt().contains("fix me please") || r.messages().size() > 1
                ? FixtureLlmProvider.text(calls.incrementAndGet() == 1 ? INVALID : prdYaml(name)) : null);
        ResponseEntity<Map> r = draftRest(Map.of("description", "Build it, fix me please: breakout above the opening range"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) r.getBody().get("attempts");
        assertThat(attempts).hasSize(2);
        assertThat((List<String>) attempts.get(0).get("errors")).anyMatch(e -> e.contains("magic_line")).anyMatch(e -> e.startsWith("stop.value"));
        assertThat((List<?>) attempts.get(1).get("errors")).isEmpty();
        LlmRequest fix = fixture.requests().get(1);
        assertThat(fix.messages()).hasSize(3);
        assertThat(fix.userPrompt()).contains("Hejje rejected that definition").contains("stop.value");
    }

    @Test
    void itGivesUpAfterThreeFixesAndSavesNothing() {
        fixture.responder(r -> "strategy-builder".equals(r.purpose()) ? FixtureLlmProvider.text(INVALID) : null);
        long before = strategies.list().size();
        ResponseEntity<Map> r = draftRest(Map.of("description", "Something the model cannot express correctly"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody()).containsEntry("created", false);
        assertThat((List<?>) r.getBody().get("attempts")).hasSize(4);
        assertThat((List<?>) r.getBody().get("errors")).isNotEmpty();
        assertThat(strategies.list()).hasSize((int) before);
        assertThat(draftTool(admin, Map.of("description", "Something the model cannot express correctly")).getBody()).containsEntry("toolStatus", "FAILED");
    }

    @Test
    void anNlDraftCannotGoLiveWithoutEvidenceAndHumanStatusChanges() {
        String name = "nl_gate_" + UUID.randomUUID().toString().substring(0, 6);
        fixture.respondWhenContains("Buy NIFTY when price closes above the first 15-minute high", prdYaml(name));
        Map<String, Object> draft = (Map<String, Object>) draftRest(Map.of("description", PRD_22)).getBody();
        String id = (String) draft.get("strategyId");
        for (String status : List.of("LIVE", "PAPER", "VALIDATED", "BACKTESTED")) {
            ResponseEntity<Map> refused = rest.exchange("/api/v1/strategies/" + id + "/versions/1/status", HttpMethod.POST,
                    new HttpEntity<>(Map.of("status", status, "note", "straight to " + status), bearer(admin)), Map.class);
            assertThat(refused.getStatusCode()).as(status).isEqualTo(HttpStatus.CONFLICT);
        }
        assertThat(strategies.version(UUID.fromString(id), 1).orElseThrow().status().name()).isEqualTo("DRAFT");
        // no agent tool changes a strategy's status
        assertThat(rest.exchange("/api/v1/agents/tools", HttpMethod.GET, new HttpEntity<>(bearer(admin)), List.class).getBody())
                .extracting(t -> ((Map<String, Object>) t).get("name")).noneMatch(n -> String.valueOf(n).contains("status"));
    }

    @Test
    void aDraftForAnExistingStrategyIsItsNextVersionAndKeepsItsName() {
        String slug = "nl_parent_" + UUID.randomUUID().toString().substring(0, 6);
        String v1 = prdYaml(slug).substring(prdYaml(slug).indexOf("name:"), prdYaml(slug).lastIndexOf("```"));
        ResponseEntity<Map> created = rest.postForEntity("/api/v1/strategies", new HttpEntity<>(Map.of("yaml", v1), bearer(admin)), Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("%s", created.getBody()).isTrue();
        fixture.respondWhenContains("Only when RSI(14) is above 50", prdYaml("some_other_name").replace("    - relative_volume > 1.5", "    - relative_volume > 1.5\n    - rsi(14) > 50"));
        Map<String, Object> draft = (Map<String, Object>) draftRest(Map.of("description", "Only when RSI(14) is above 50", "strategy", slug)).getBody();
        assertThat(draft).containsEntry("created", true).containsEntry("slug", slug).containsEntry("version", 2).containsEntry("parentVersion", 1)
                .containsEntry("changeNote", "NL draft").containsEntry("status", "DRAFT");
        assertThat((String) draft.get("parentYaml")).contains("name: " + slug).doesNotContain("rsi(14)");
        assertThat((String) draft.get("yaml")).contains("name: " + slug).contains("rsi(14) > 50");
        assertThat((List<String>) draft.get("rules")).contains("• RSI(14) is above 50");
    }

    @Test
    void draftingNeedsStrategiesWrite() {
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String research = clients.create("nl-research-" + UUID.randomUUID(), AgentPresets.scopes("research"), null, actor).key();
        assertThat(draftTool(research, Map.of("description", PRD_22)).getBody()).containsEntry("toolStatus", "FORBIDDEN");
        assertThat(fixture.requests()).isEmpty();
    }
}
