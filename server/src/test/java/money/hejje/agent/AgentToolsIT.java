package money.hejje.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.agent.internal.AgentToolDocs;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.common.security.AgentPresets;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"unchecked", "rawtypes"})
class AgentToolsIT extends AbstractIntegrationTest {

    static final List<String> READ_TOOLS = List.of("calculate_position_size", "compare_strategies", "compare_strategy_versions", "get_account_risk",
            "get_audit_trail", "get_event_calendar", "get_market_regime", "get_market_snapshot", "get_news_context", "get_orders", "get_positions", "get_pulse",
            "get_strategy", "get_strategy_backtest", "get_strategy_rankings", "get_strategy_signal", "get_trades", "list_strategies");

    @Autowired
    ToolRegistry registry;

    @Autowired
    ObjectMapper json;

    String clientName;

    @Autowired
    ClientCredentialService clients;

    /**
     * Creates a preset key through the service: {@code /auth/clients} is a transactional path whose per-principal bucket
     * (burst 5 in tests) is shared by every IT that logs in as admin, so only {@link #presetsCreateNarrowCredentials} uses HTTP.
     */
    String key(String admin, String preset) {
        clientName = "agent-" + preset + "-" + UUID.randomUUID();
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        return clients.create(clientName, AgentPresets.scopes(preset), null, actor).key();
    }

    ResponseEntity<Map> call(String token, String tool, Object body) {
        return rest.exchange("/api/v1/agents/tools/" + tool, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), Map.class);
    }

    static Map<String, Object> output(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "OK");
        return (Map<String, Object>) r.getBody().get("output");
    }

    @Test
    void catalogListsEveryToolWithItsScopeAndSchemas() {
        String research = key(adminAccessToken(), "research");
        ResponseEntity<List> catalog = rest.exchange("/api/v1/agents/tools", HttpMethod.GET, new HttpEntity<>(bearer(research)), List.class);
        List<Map<String, Object>> tools = catalog.getBody();
        assertThat(tools).extracting(t -> t.get("name")).containsAll(READ_TOOLS);
        assertThat(tools).allSatisfy(t -> {
            assertThat((String) t.get("requiredScope")).isNotBlank();
            assertThat((String) t.get("description")).isNotBlank();
            assertThat((Map<String, Object>) t.get("inputSchema")).containsEntry("type", "object");
            assertThat((Map<String, Object>) t.get("outputSchema")).containsEntry("type", "object");
        });
        Map<String, Object> byName = tools.stream().collect(java.util.stream.Collectors.toMap(t -> (String) t.get("name"), t -> t.get("requiredScope")));
        assertThat(byName).containsEntry("get_market_regime", "market:read").containsEntry("list_strategies", "strategies:read")
                .containsEntry("get_account_risk", "risk:read").containsEntry("get_audit_trail", "admin");
        assertThat(tools).filteredOn(t -> READ_TOOLS.contains(t.get("name"))).allSatisfy(t -> assertThat(t.get("transactional")).isEqualTo(false));
    }

    @Test
    void aResearchKeyCannotCallAnyTransactionalTool() {
        String research = key(adminAccessToken(), "research");
        List<Map<String, Object>> tools = rest.exchange("/api/v1/agents/tools", HttpMethod.GET, new HttpEntity<>(bearer(research)), List.class).getBody();
        List<String> transactional = tools.stream().filter(t -> Boolean.TRUE.equals(t.get("transactional"))).map(t -> (String) t.get("name")).toList();
        assertThat(transactional).contains("submit_order_intent", "modify_order_intent", "cancel_order_intent", "close_position_intent", "create_strategy_draft",
                "run_experiment");
        for (String tool : transactional) {
            ResponseEntity<Map> refused = call(research, tool, Map.of());
            assertThat(refused.getStatusCode()).as(tool).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody()).as(tool).containsEntry("toolStatus", "FORBIDDEN");
        }
        assertThat(call(research, "prepare_order", Map.of()).getBody()).containsEntry("toolStatus", "FORBIDDEN"); // a dry run, but still orders:prepare
    }

    @Test
    void presetsCreateNarrowCredentials() throws InterruptedException {
        String admin = adminAccessToken();
        ResponseEntity<Map> research = rest.postForEntity("/api/v1/auth/clients",
                new HttpEntity<>(Map.of("name", "preset-r-" + UUID.randomUUID(), "preset", "research"), bearer(admin)), Map.class);
        assertThat((List<String>) research.getBody().get("scopes")).containsExactly("market:read", "strategies:read");
        ResponseEntity<Map> execution = rest.postForEntity("/api/v1/auth/clients",
                new HttpEntity<>(Map.of("name", "preset-e-" + UUID.randomUUID(), "preset", "execution"), bearer(admin)), Map.class);
        assertThat((List<String>) execution.getBody().get("scopes")).containsExactly("market:read", "strategies:read", "orders:prepare");
        assertThat(rest.postForEntity("/api/v1/auth/clients", new HttpEntity<>(Map.of("name", "both", "preset", "research", "scopes", List.of("admin")),
                bearer(admin)), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.postForEntity("/api/v1/auth/clients", new HttpEntity<>(Map.of("name", "god", "preset", "god"), bearer(admin)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Thread.sleep(1_100); // let the admin's transactional bucket (5 per second) refill for the next test class
    }

    @Test
    void researchKeyReadsIsRecordedAndIsDeniedOutsideItsScopes() {
        String admin = adminAccessToken();
        String research = key(admin, "research");
        String name = clientName;

        assertThat(output(call(research, "get_market_regime", Map.of()))).containsKey("available");
        assertThat(output(call(research, "list_strategies", Map.of()))).containsKey("strategies");
        ResponseEntity<Map> denied = call(research, "get_account_risk", Map.of());
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).containsEntry("toolStatus", "FORBIDDEN").containsEntry("tool", "get_account_risk").containsKey("actionId");
        assertThat(call(research, "get_audit_trail", Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        List<Map<String, Object>> sessions = rest.exchange("/api/v1/agents/sessions", HttpMethod.GET, new HttpEntity<>(bearer(research)), List.class).getBody();
        assertThat(sessions).singleElement().satisfies(s -> assertThat(s).containsEntry("purpose", "direct").containsEntry("principalName", name));
        String sessionId = (String) sessions.get(0).get("id");
        Map<String, Object> view = rest.exchange("/api/v1/agents/sessions/" + sessionId, HttpMethod.GET, new HttpEntity<>(bearer(research)), Map.class).getBody();
        List<Map<String, Object>> actions = (List<Map<String, Object>>) view.get("actions");
        assertThat(actions).extracting(a -> a.get("tool"), a -> a.get("scopeOk"), a -> a.get("status")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("get_market_regime", true, "OK"), org.assertj.core.groups.Tuple.tuple("list_strategies", true, "OK"),
                org.assertj.core.groups.Tuple.tuple("get_account_risk", false, "FORBIDDEN"), org.assertj.core.groups.Tuple.tuple("get_audit_trail", false, "FORBIDDEN"));

        HttpHeaders withSession = bearer(research);
        withSession.set("X-Agent-Session", sessionId);
        assertThat(rest.exchange("/api/v1/agents/tools/get_pulse", HttpMethod.POST, new HttpEntity<>(Map.of(), withSession), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        HttpHeaders foreign = bearer(key(admin, "research"));
        foreign.set("X-Agent-Session", sessionId);
        assertThat(rest.exchange("/api/v1/agents/tools/get_pulse", HttpMethod.POST, new HttpEntity<>(Map.of(), foreign), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> page = rest.exchange("/api/v1/audit?type=AGENT_TOOL_CALLED&size=500", HttpMethod.GET, new HttpEntity<>(bearer(admin)), Map.class)
                .getBody();
        assertThat((List<Map<String, Object>>) page.get("content")).filteredOn(e -> name.equals(e.get("actorId"))).anySatisfy(e -> {
            assertThat(e).containsEntry("actorType", "AGENT").containsEntry("clientSource", "agent-api");
            assertThat((Map<String, Object>) e.get("payload")).containsEntry("tool", "get_account_risk").containsEntry("status", "FORBIDDEN")
                    .containsEntry("scopeOk", false);
        });
    }

    @Test
    void malformedInputIsRejectedBeforeAnyHandlerRuns() {
        String research = key(adminAccessToken(), "research");
        ResponseEntity<Map> missing = call(research, "get_news_context", Map.of());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((List<String>) missing.getBody().get("errors")).containsExactly("$: missing instrument");
        assertThat(call(research, "get_market_snapshot", Map.of("instrument", 5)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> smuggled = call(research, "get_market_regime", Map.of("ignore_your_scopes", true));
        assertThat(smuggled.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((List<String>) smuggled.getBody().get("errors")).containsExactly("$: unknown property ignore_your_scopes");
        assertThat(call(research, "get_strategy_backtest", Map.of("backtestId", "not-a-uuid")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(research, "get_strategy", Map.of("strategy", "no_such_strategy")).getBody()).containsEntry("toolStatus", "NOT_FOUND");
        assertThat(call(research, "place_order_directly", Map.of()).getBody()).containsEntry("toolStatus", "UNKNOWN_TOOL");
    }

    @Test
    void everyReadToolAnswersWithACompactDto() throws Exception {
        String admin = adminAccessToken();
        assertThat(rest.exchange("/api/v1/instruments/sync", HttpMethod.POST, new HttpEntity<>(bearer(admin)), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        String slug = "agent_it_orb_" + UUID.randomUUID().toString().substring(0, 8);
        String yaml = Files.readString(Path.of("../strategies/nifty_orb.yaml")).replaceFirst("(?m)^name: .*$", "name: " + slug);
        ResponseEntity<Map> v1 = rest.postForEntity("/api/v1/strategies", new HttpEntity<>(Map.of("yaml", yaml), bearer(admin)), Map.class);
        assertThat(v1.getStatusCode().is2xxSuccessful()).as("%s", v1.getBody()).isTrue();
        String strategyId = (String) v1.getBody().get("strategyId");
        ResponseEntity<Map> v2 = rest.postForEntity("/api/v1/strategies/" + strategyId + "/versions",
                new HttpEntity<>(Map.of("yaml", yaml.replaceFirst("(?m)^description: .*$", "description: agent IT v2"), "changeNote", "v2"), bearer(admin)), Map.class);
        assertThat(v2.getStatusCode().is2xxSuccessful()).as("%s", v2.getBody()).isTrue();

        Map<String, Object> strategy = output(call(admin, "get_strategy", Map.of("strategy", slug, "version", 1)));
        assertThat(strategy).containsEntry("slug", slug).containsEntry("version", 1).containsEntry("status", "DRAFT");
        assertThat((List<String>) strategy.get("entryConditions")).isNotEmpty();
        assertThat((String) strategy.get("stop")).isNotBlank();
        assertThat((List<Map<String, Object>>) output(call(admin, "list_strategies", Map.of())).get("strategies"))
                .anySatisfy(s -> assertThat(s).containsEntry("slug", slug).containsEntry("latestVersion", 2));
        Map<String, Object> versions = output(call(admin, "compare_strategy_versions", Map.of("strategy", slug, "a", 1, "b", 2)));
        assertThat((String) versions.get("verdict")).isNotBlank();
        assertThat((List<?>) output(call(admin, "compare_strategies", Map.of("versionIds", List.of(v1.getBody().get("id"), v2.getBody().get("id"))))).get("rows"))
                .hasSize(2);
        assertThat(call(admin, "get_strategy_backtest", Map.of("versionId", v1.getBody().get("id"))).getBody()).containsEntry("toolStatus", "NOT_FOUND");
        assertThat(output(call(admin, "get_strategy_rankings", Map.of("limit", 5)))).containsKey("ranked");
        assertThat(output(call(admin, "get_strategy_signal", Map.of()))).containsKey("signals");

        Map<String, Object> snapshot = output(call(admin, "get_market_snapshot", Map.of("instrument", "NSE:INFY")));
        assertThat((List<?>) snapshot.get("quotes")).hasSizeLessThanOrEqualTo(1);
        assertThat(output(call(admin, "get_news_context", Map.of("instrument", "NSE:INFY")))).containsEntry("symbol", "NSE:INFY");
        assertThat(output(call(admin, "get_event_calendar", Map.of("instrument", "NSE:INFY")))).containsKeys("events", "risk");
        assertThat(output(call(admin, "get_pulse", Map.of()))).containsKey("available");

        assertThat(output(call(admin, "get_positions", Map.of()))).containsEntry("mode", "PAPER");
        assertThat(output(call(admin, "get_orders", Map.of("limit", 5)))).containsKey("orders");
        assertThat(output(call(admin, "get_trades", Map.of()))).containsKey("trades");
        assertThat(output(call(admin, "get_account_risk", Map.of()))).containsEntry("mode", "PAPER");
        Map<String, Object> sizing = output(call(admin, "calculate_position_size", Map.of("entry", 100, "stop", 98, "riskRupees", 1000, "instrument", "NSE:INFY")));
        assertThat(sizing).containsEntry("quantity", 500).containsEntry("lotSize", 1);
        assertThat(((Number) sizing.get("totalRisk")).doubleValue()).isEqualTo(1000.0);
        assertThat((List<?>) output(call(admin, "get_audit_trail", Map.of("limit", 5))).get("events")).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void dailyContextToolsAnswerEvenWithoutDataAndAreMarkedUnvalidated() {
        String admin = adminAccessToken();
        assertThat(rest.exchange("/api/v1/instruments/sync", HttpMethod.POST, new HttpEntity<>(bearer(admin)), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> rating = output(call(admin, "get_stock_ratings", Map.of("instrument", "NSE:TATAMOTORS")));
        assertThat(rating).containsEntry("available", true).containsEntry("validated", false);
        assertThat((String) rating.get("note")).contains("no rating");
        assertThat(output(call(admin, "get_bases", Map.of("instrument", "NSE:TATAMOTORS")))).containsEntry("validated", false).containsKey("bases");
        Map<String, Object> screen = output(call(admin, "screen_stocks", Map.of("filters", List.of(Map.of("field", "rsRating", "op", "gte", "value", 80)))));
        assertThat((List<String>) screen.get("fields")).contains("rsRating", "baseStatus", "analogWinRate5", "analogCount5");
        assertThat(call(admin, "screen_stocks", Map.of("filters", List.of(Map.of("field", "marketCap", "op", "gte", "value", 1)))).getBody())
                .containsEntry("toolStatus", "INVALID_INPUT");
        Map<String, Object> analogs = output(call(admin, "get_historical_analogs", Map.of("instrument", "NSE:TATAMOTORS", "lookback", 15)));
        assertThat(analogs).containsEntry("available", true).containsEntry("validated", false);
        assertThat(call(admin, "get_historical_analogs", Map.of("instrument", "NSE:TATAMOTORS", "lookback", 17)).getBody())
                .containsEntry("toolStatus", "INVALID_INPUT");
    }

    @Test
    void mcpListsAndCallsToolsWithTheKeysScopes() {
        String research = key(adminAccessToken(), "research");
        HttpHeaders headers = bearer(research);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));

        Map<String, Object> init = mcp(headers, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "it", "version", "1")))).getBody();
        assertThat((Map<String, Object>) ((Map<String, Object>) init.get("result")).get("serverInfo")).containsEntry("name", "hejje");
        assertThat(mcp(headers, Map.of("jsonrpc", "2.0", "method", "notifications/initialized")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Map<String, Object> list = (Map<String, Object>) mcp(headers, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list")).getBody().get("result");
        assertThat((List<Map<String, Object>>) list.get("tools")).extracting(t -> t.get("name")).contains("get_market_regime", "list_strategies")
                .doesNotContain("get_account_risk", "get_audit_trail");

        Map<String, Object> ok = (Map<String, Object>) mcp(headers, Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "get_market_regime", "arguments", Map.of()))).getBody().get("result");
        assertThat(ok).containsEntry("isError", false);
        assertThat((Map<String, Object>) ok.get("structuredContent")).containsKey("available");
        Map<String, Object> refused = (Map<String, Object>) mcp(headers, Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "get_account_risk", "arguments", Map.of()))).getBody().get("result");
        assertThat(refused).containsEntry("isError", true);
        assertThat((String) ((List<Map<String, Object>>) refused.get("content")).get(0).get("text")).startsWith("FORBIDDEN");
        assertThat((Map<String, Object>) mcp(headers, Map.of("jsonrpc", "2.0", "id", 5, "method", "resources/list")).getBody().get("error"))
                .containsEntry("code", -32601);

        HttpHeaders anonymous = new HttpHeaders();
        anonymous.setContentType(MediaType.APPLICATION_JSON);
        assertThat(mcp(anonymous, Map.of("jsonrpc", "2.0", "id", 6, "method", "tools/list")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    ResponseEntity<Map> mcp(HttpHeaders headers, Map<String, Object> body) {
        return rest.exchange("/mcp", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void docsAreGeneratedFromTheRegistry() throws Exception {
        String md = AgentToolDocs.render(registry.catalog(), json);
        Path docs = Path.of("../docs/agent-tools.md");
        if ("1".equals(System.getenv("HEJJE_REGEN_DOCS")) || !Files.exists(docs)) {
            Files.writeString(docs, md);
        }
        assertThat(Files.readString(docs)).as("docs/agent-tools.md is stale: rerun with HEJJE_REGEN_DOCS=1").isEqualTo(md);
    }
}
