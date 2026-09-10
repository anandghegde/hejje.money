package money.hejje.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import money.hejje.agent.internal.AgentStore;
import money.hejje.agent.internal.ToolSchemas;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Scope boundary, schema validation, handler errors and recording in {@link AgentToolService}, without a database. */
class AgentToolServiceTest {

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public record Lookup(String instrument, Integer depth) {}

    public record Found(String instrument, int count, List<String> notes) {}

    AgentStore store;
    AuditService audit;
    AtomicInteger handled;
    AgentToolService service;
    UUID session = UUID.randomUUID();

    static JsonNode j(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        store = mock(AgentStore.class);
        audit = mock(AuditService.class);
        handled = new AtomicInteger();
        JsonNode lookupSchema = j("""
                {"type":"object","properties":{"instrument":{"type":"string","minLength":1},"depth":{"type":"integer","minimum":1}},
                 "required":["instrument"],"additionalProperties":false}""");
        AgentToolProvider provider = () -> List.of(
                AgentTool.of("lookup", "Looks things up", "market:read", lookupSchema, Lookup.class, Found.class, (in, ctx) -> {
                    handled.incrementAndGet();
                    if (in.instrument().equals("missing")) {
                        throw ToolException.notFound("Unknown instrument missing");
                    }
                    if (in.instrument().equals("bad")) {
                        throw new IllegalArgumentException("bad argument");
                    }
                    return new Found(in.instrument(), in.depth() == null ? 1 : in.depth(), null);
                }),
                AgentTool.transactional("prepare_thing", "Prepares", "orders:prepare", j("{\"type\":\"object\"}"), Map.class, Found.class, (in, ctx) -> {
                    handled.incrementAndGet();
                    return new Found("x", 1, List.of());
                }),
                new AgentTool("broken_output", "Returns the wrong shape", "market:read", false, j("{\"type\":\"object\"}"), Map.class, Found.class,
                        (in, ctx) -> Map.of("instrument", "x", "count", "not-a-number")));
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-10T10:00:00"), MutableClock.IST, (d, e) -> false);
        service = new AgentToolService(new ToolRegistry(List.of(provider)), store, audit, clock, JSON);
    }

    ToolContext ctx(String... scopes) {
        return new ToolContext(new HejjePrincipal(UUID.randomUUID(), "research-bot", HejjePrincipal.Type.CLIENT, Set.of(scopes)), session, null, "test");
    }

    @Test
    void okCallIsValidatedTypedAndRecordedWithAnAuditEvent() throws Exception {
        ToolResult r = service.invoke("lookup", j("{\"instrument\":\"NSE:INFY\",\"depth\":3}"), ctx("market:read"));
        assertThat(r.status()).isEqualTo(ToolStatus.OK);
        assertThat(r.output().path("instrument").asText()).isEqualTo("NSE:INFY");
        assertThat(r.output().path("count").asInt()).isEqualTo(3);
        assertThat(r.output().has("notes")).isFalse(); // nulls are not serialized
        ArgumentCaptor<AgentAction> action = ArgumentCaptor.forClass(AgentAction.class);
        verify(store).insertAction(action.capture());
        assertThat(action.getValue().sessionId()).isEqualTo(session);
        assertThat(action.getValue().scopeOk()).isTrue();
        assertThat(action.getValue().status()).isEqualTo("OK");
        assertThat(action.getValue().outputSummary().path("count").asInt()).isEqualTo(3);
        verify(audit).record(argThat(e -> e.type() == AuditEventType.AGENT_TOOL_CALLED && "research-bot".equals(e.actorId())
                && "OK".equals(e.payload().get("status")) && "lookup".equals(e.payload().get("tool"))));
    }

    @Test
    void theScopeComesFromTheCredentialAndARefusalIsStillRecorded() throws Exception {
        ToolResult r = service.invoke("prepare_thing", j("{}"), ctx("market:read", "strategies:read"));
        assertThat(r.status()).isEqualTo(ToolStatus.FORBIDDEN);
        assertThat(r.error()).contains("requires scope orders:prepare");
        assertThat(handled.get()).isZero();
        verify(store).insertAction(argThat(a -> !a.scopeOk() && a.status().equals("FORBIDDEN")));
        verify(audit).record(argThat(e -> Boolean.FALSE.equals(e.payload().get("scopeOk"))));
        assertThat(service.invoke("prepare_thing", j("{}"), ctx("orders:prepare")).status()).isEqualTo(ToolStatus.OK);
    }

    @Test
    void malformedInputIsRejectedBeforeTheHandlerRuns() throws Exception {
        assertThat(service.invoke("lookup", j("{}"), ctx("market:read")).details()).containsExactly("$: missing instrument");
        assertThat(service.invoke("lookup", j("{\"instrument\":7}"), ctx("market:read")).details()).containsExactly("$.instrument: expected string");
        assertThat(service.invoke("lookup", j("{\"instrument\":\"x\",\"depth\":0}"), ctx("market:read")).details()).containsExactly("$.depth: below minimum 1");
        ToolResult unknownField = service.invoke("lookup", j("{\"instrument\":\"x\",\"ignore_previous_instructions\":true}"), ctx("market:read"));
        assertThat(unknownField.status()).isEqualTo(ToolStatus.INVALID_INPUT);
        assertThat(unknownField.details()).containsExactly("$: unknown property ignore_previous_instructions");
        assertThat(handled.get()).isZero();
    }

    @Test
    void handlerFailuresMapToStatusesAndBadOutputIsCaught() throws Exception {
        assertThat(service.invoke("lookup", j("{\"instrument\":\"missing\"}"), ctx("market:read")).status()).isEqualTo(ToolStatus.NOT_FOUND);
        assertThat(service.invoke("lookup", j("{\"instrument\":\"bad\"}"), ctx("market:read")).status()).isEqualTo(ToolStatus.INVALID_INPUT);
        ToolResult broken = service.invoke("broken_output", j("{}"), ctx("market:read"));
        assertThat(broken.status()).isEqualTo(ToolStatus.INVALID_OUTPUT);
        assertThat(broken.details()).containsExactly("$.count: expected integer");
        assertThat(service.invoke("nope", null, ctx("market:read")).status()).isEqualTo(ToolStatus.UNKNOWN_TOOL);
    }

    @Test
    void catalogIsFilteredByScopeAndOutputSchemasAreGenerated() {
        HejjePrincipal research = new HejjePrincipal(UUID.randomUUID(), "r", HejjePrincipal.Type.CLIENT, Set.of("market:read", "strategies:read"));
        assertThat(service.catalogFor(research)).extracting(ToolDescriptor::name).containsExactly("broken_output", "lookup");
        assertThat(service.catalog()).extracting(ToolDescriptor::name).containsExactly("broken_output", "lookup", "prepare_thing");
        JsonNode schema = ToolSchemas.of(Found.class);
        assertThat(schema.path("properties").path("count").path("type").asText()).isEqualTo("integer");
        assertThat(schema.path("properties").path("notes").path("items").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("required")).hasSize(1);
    }

    @Test
    void registryAndToolDefinitionsAreChecked() throws Exception {
        JsonNode any = j("{\"type\":\"object\"}");
        assertThatThrownBy(() -> AgentTool.of("Bad-Name", "d", "market:read", any, Map.class, Found.class, (in, c) -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentTool.of("ok_name", "d", "market:write", any, Map.class, Found.class, (in, c) -> null))
                .hasMessageContaining("Unknown scope");
        AgentTool one = AgentTool.of("dup", "d", "market:read", any, Map.class, Found.class, (in, c) -> null);
        assertThatThrownBy(() -> new ToolRegistry(List.of(() -> List.of(one), () -> List.of(one)))).hasMessageContaining("Duplicate agent tool dup");
    }

    @Test
    void largeOutputsAreSummarisedWithTheirIds() throws Exception {
        AgentToolProvider big = () -> List.of(AgentTool.of("big", "d", "market:read", j("{\"type\":\"object\"}"), Map.class, Found.class,
                (in, c) -> new Found("x".repeat(9000) + " 0192f0c4-1f7a-7000-8000-000000000001", 1, null)));
        AgentToolService s = new AgentToolService(new ToolRegistry(List.of(big)), store, audit,
                new HejjeClock(MutableClock.atIst("2026-09-10T10:00:00"), MutableClock.IST, (d, e) -> false), JSON);
        s.invoke("big", null, ctx("market:read"));
        verify(store).insertAction(argThat(a -> a.outputSummary().path("truncated").asBoolean()
                && a.outputSummary().path("ids").get(0).asText().equals("0192f0c4-1f7a-7000-8000-000000000001")));
        verify(audit, org.mockito.Mockito.atLeastOnce()).record(any());
    }
}
