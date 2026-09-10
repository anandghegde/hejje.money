package money.hejje.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.common.security.AgentPresets;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.llm.FixtureLlmProvider;
import money.hejje.llm.LlmMessage;
import money.hejje.llm.LlmRequest;
import money.hejje.llm.LlmResponse;
import money.hejje.llm.LlmService;
import money.hejje.llm.LlmTool;
import money.hejje.llm.LlmToolCall;
import money.hejje.strategy.StrategyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Hejje AI with the fixture LLM: canonical questions call the expected tools; grounding, step limit, follow-ups, scopes, SSE. */
@SuppressWarnings({"unchecked", "rawtypes"})
class HejjeAiIT extends AbstractIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    LlmService llm;

    @Autowired
    StrategyService strategies;

    @Autowired
    ClientCredentialService clients;

    FixtureLlmProvider fixture;
    UUID deploymentId;

    @BeforeEach
    void setUp() {
        fixture = (FixtureLlmProvider) llm.provider("fixture").orElseThrow();
        fixture.reset();
        fixture.responder(this::script);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        fixture.reset();
        if (deploymentId != null) {
            strategies.updateDeployment(deploymentId, false, "HejjeAiIT done", "it");
            awaitAsyncListeners();
        }
    }

    static LlmToolCall call(String tool, String args) {
        try {
            return new LlmToolCall(null, tool, JSON.readTree(args));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A scripted "model": picks tools by keyword until tool results arrive, then answers citing them. */
    LlmResponse script(LlmRequest r) {
        boolean toolResults = r.messages().stream().anyMatch(m -> m.role() == LlmMessage.Role.TOOL);
        String q = r.userPrompt();
        if (q.contains("Evidence (fetched by Hejje")) {
            return FixtureLlmProvider.text("Here is the explanation from the evidence [get_strategy_rankings].");
        }
        if (q.contains("loop forever")) {
            return FixtureLlmProvider.calls(call("get_pulse", "{}"));
        }
        if (!toolResults) {
            if (q.contains("market regime") || q.contains("fabricate")) {
                return FixtureLlmProvider.calls(call("get_market_regime", "{}"));
            }
            if (q.contains("positions and account risk")) {
                return FixtureLlmProvider.calls(call("get_positions", "{}"), call("get_account_risk", "{}"));
            }
            if (q.contains("size")) {
                return FixtureLlmProvider.calls(call("calculate_position_size", "{\"entry\":100,\"stop\":98,\"riskRupees\":1000}"));
            }
            return FixtureLlmProvider.text("I can answer questions about Hejje.");
        }
        if (q.contains("fabricate")) {
            return FixtureLlmProvider.text("The trend is labelled and the regime score is 97 [get_market_regime].");
        }
        if (q.contains("size")) {
            return FixtureLlmProvider.text("Buy 500 shares, risking 1,000 rupees [calculate_position_size].");
        }
        LlmMessage lastTool = r.messages().stream().filter(m -> m.role() == LlmMessage.Role.TOOL).reduce((a, b) -> b).orElseThrow();
        return FixtureLlmProvider.text("Done [" + lastTool.toolName() + "].");
    }

    Map<String, Object> ask(String token, String question, String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        HttpHeaders headers = bearer(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<Map> r = rest.exchange("/api/v1/agents/ai/ask", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static List<String> tools(Map<String, Object> turn) {
        return ((List<Map<String, Object>>) turn.get("trace")).stream().map(t -> (String) t.get("tool")).toList();
    }

    String createStrategy(String admin, String slug) {
        String yaml = "name: " + slug + "\nuniverse: [NSE:INFY]\ntimeframe: 5m\ndirection: long\nentry:\n  all:\n    - close > opening_range_high\nstop:\n"
                + "  type: opening_range_low\ntarget:\n  type: risk_multiple\n  value: 2\ntrade_window:\n  start: \"09:30\"\n  end: \"15:00\"\nmax_trades_per_day: 1\n";
        ResponseEntity<Map> created = rest.postForEntity("/api/v1/strategies", new HttpEntity<>(Map.of("yaml", yaml), bearer(admin)), Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("%s", created.getBody()).isTrue();
        return (String) created.getBody().get("strategyId");
    }

    @Test
    void fiveCanonicalQuestionsCallTheExpectedTools() throws Exception {
        String admin = adminAccessToken();
        rest.exchange("/api/v1/instruments/sync", HttpMethod.POST, new HttpEntity<>(bearer(admin)), Map.class);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String a = "ai_orb_a_" + suffix;
        String b = "ai_orb_b_" + suffix;
        String aId = createStrategy(admin, a);
        createStrategy(admin, b);
        rest.postForEntity("/api/v1/strategies/" + aId + "/versions/1/status", new HttpEntity<>(Map.of("status", "PAPER", "force", true, "note", "ai it"),
                bearer(admin)), Map.class);
        ResponseEntity<Map> deployment = rest.postForEntity("/api/v1/strategies/" + aId + "/versions/1/deployments", new HttpEntity<>(Map.of("mode", "PAPER",
                "instruments", List.of("NSE:INFY"), "autonomyLevel", 0, "params", Map.of("risk_rupees", 2000)), bearer(admin)), Map.class);
        assertThat(deployment.getStatusCode().is2xxSuccessful()).as("%s", deployment.getBody()).isTrue();
        deploymentId = UUID.fromString((String) deployment.getBody().get("id"));
        awaitAsyncListeners();

        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("What is working today?", List.of("get_pnl_breakdown", "get_strategy_rankings"));
        expected.put("Why is " + a + " ranked first?", List.of("get_strategy_rankings", "get_strategy"));
        expected.put("Compare " + a + " and " + b, List.of("list_strategies", "compare_strategies"));
        expected.put("What is the market regime right now?", List.of("get_market_regime"));
        expected.put("Show my open positions and account risk", List.of("get_positions", "get_account_risk"));
        Map<String, String> flows = Map.of("What is working today?", "working_today", "Why is " + a + " ranked first?", "why_ranked_first",
                "Compare " + a + " and " + b, "compare");
        for (Map.Entry<String, List<String>> e : expected.entrySet()) {
            Map<String, Object> turn = ask(admin, e.getKey(), null);
            assertThat(tools(turn)).as(e.getKey()).containsExactlyElementsOf(e.getValue());
            assertThat((List<Map<String, Object>>) turn.get("trace")).as(e.getKey()).allSatisfy(t -> assertThat(t.get("status")).isEqualTo("OK"));
            assertThat((String) turn.get("answer")).isNotBlank();
            assertThat(turn.get("flow")).isEqualTo(flows.get(e.getKey()));
            assertThat(turn.get("profile")).isEqualTo("reasoning");
        }
        // the why-flow's evidence carries the ranking row and the rules; the model saw it with the question
        LlmRequest why = fixture.requests().stream().filter(r -> r.userPrompt().startsWith("Why is " + a)).findFirst().orElseThrow();
        assertThat(why.userPrompt()).contains("Evidence (fetched by Hejje").contains(a + " v1 on NSE:INFY is #").contains("Rules [get_strategy]: entry close > opening_range_high");
        assertThat(why.systemPrompt()).contains("Citation rule").contains("PAPER mode");
    }

    @Test
    void fabricatedNumbersAreFlaggedAndCopiedNumbersVerified() {
        String admin = adminAccessToken();
        Map<String, Object> fabricated = ask(admin, "What is the market regime? fabricate", null);
        Map<String, Object> grounding = (Map<String, Object>) fabricated.get("grounding");
        assertThat((List<String>) grounding.get("unverifiedNumbers")).containsExactly("97");

        Map<String, Object> sizing = ask(admin, "What size should I trade at 100 with a stop at 98?", null);
        assertThat(tools(sizing)).containsExactly("calculate_position_size");
        Map<String, Object> ok = (Map<String, Object>) sizing.get("grounding");
        assertThat((List<String>) ok.get("verifiedNumbers")).containsExactly("500", "1,000");
        assertThat((List<String>) ok.get("unverifiedNumbers")).isEmpty();
    }

    @Test
    void theToolLoopStopsAfterEightSteps() {
        Map<String, Object> turn = ask(adminAccessToken(), "loop forever please", null);
        assertThat(turn).containsEntry("steps", 8).containsEntry("stepLimitReached", true);
        assertThat(tools(turn)).hasSize(8).allMatch("get_pulse"::equals);
        assertThat((String) turn.get("answer")).contains("stopped after 8 steps");
    }

    @Test
    void followUpsUseTheFastProfileAndReplayTheConversation() {
        String admin = adminAccessToken();
        Map<String, Object> first = ask(admin, "What is the market regime right now?", null);
        String conversationId = (String) first.get("conversationId");
        int before = fixture.requests().size();
        Map<String, Object> second = ask(admin, "And my positions and account risk?", conversationId);
        assertThat(second).containsEntry("conversationId", conversationId).containsEntry("profile", "fast");
        LlmRequest followUp = fixture.requests().get(before);
        assertThat(followUp.profile()).isEqualTo("fast");
        assertThat(followUp.messages()).extracting(LlmMessage::role).startsWith(LlmMessage.Role.USER, LlmMessage.Role.ASSISTANT, LlmMessage.Role.USER);
        assertThat(followUp.messages().get(0).content()).isEqualTo("What is the market regime right now?");

        Map<String, Object> view = rest.exchange("/api/v1/agents/ai/conversations/" + conversationId, HttpMethod.GET, new HttpEntity<>(bearer(admin)), Map.class)
                .getBody();
        List<Map<String, Object>> messages = (List<Map<String, Object>>) view.get("messages");
        assertThat(messages).extracting(m -> m.get("role")).containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
        assertThat((List<?>) messages.get(3).get("trace")).hasSize(2);
        assertThat((List<Map<String, Object>>) rest.exchange("/api/v1/agents/ai/conversations", HttpMethod.GET, new HttpEntity<>(bearer(admin)), List.class)
                .getBody()).anySatisfy(c -> assertThat(c.get("id")).isEqualTo(conversationId));
    }

    @Test
    void aResearchKeyChatCannotReachToolsOutsideItsScopes() {
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String key = clients.create("ai-research-" + UUID.randomUUID(), AgentPresets.scopes("research"), null, actor).key();
        Map<String, Object> turn = ask(key, "Show my open positions and account risk", null);
        assertThat((List<Map<String, Object>>) turn.get("trace")).extracting(t -> t.get("tool"), t -> t.get("status"), t -> t.get("requiredScope"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("get_positions", "OK", "market:read"),
                        org.assertj.core.groups.Tuple.tuple("get_account_risk", "FORBIDDEN", "risk:read"));
        // the model was never offered the tool; the scripted call was refused at the tool boundary anyway
        assertThat(fixture.requests().get(0).tools()).extracting(LlmTool::name).contains("get_positions").doesNotContain("get_account_risk", "get_audit_trail");
    }

    @Test
    void answersStreamAsServerSentEvents() throws Exception {
        HttpHeaders headers = bearer(adminAccessToken());
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        ResponseEntity<String> r = rest.exchange("/api/v1/agents/ai/ask", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "What is the market regime right now?"), headers), String.class);
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_EVENT_STREAM)).isTrue();
        String body = r.getBody();
        assertThat(body).contains("event: tool\ndata: {").contains("\"tool\":\"get_market_regime\"").contains("event: delta").contains("event: done");
        String done = body.substring(body.indexOf("event: done\ndata: ") + "event: done\ndata: ".length()).split("\n\n")[0];
        JsonNode turn = JSON.readTree(done);
        assertThat(turn.path("answer").asText()).isEqualTo("Done [get_market_regime].");

        ResponseEntity<String> blank = rest.exchange("/api/v1/agents/ai/ask", HttpMethod.POST, new HttpEntity<>(Map.of("question", " "), headers), String.class);
        assertThat(blank.getBody()).contains("event: error").contains("question is required");
    }

    @Test
    void statusReportsProfilesAndLimits() {
        Map<String, Object> status = rest.exchange("/api/v1/agents/ai/status", HttpMethod.GET, new HttpEntity<>(bearer(adminAccessToken())), Map.class).getBody();
        assertThat(status).containsEntry("enabled", true).containsEntry("profile", "reasoning").containsEntry("followUpProfile", "fast").containsEntry("maxSteps", 8);
    }
}
