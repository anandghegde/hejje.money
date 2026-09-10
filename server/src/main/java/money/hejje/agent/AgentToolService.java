package money.hejje.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import money.hejje.agent.internal.AgentStore;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.CorrelationContext;
import money.hejje.common.Ids;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.HejjeClock;
import money.hejje.llm.JsonSchemaLite;
import money.hejje.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Invokes registered tools on behalf of a credential: scope check (from the credential only) → input schema → typed
 * handler → output schema, then records an {@code agent_action} row and an {@code AGENT_TOOL_CALLED} audit event for
 * every call, including refused ones. Also manages agent sessions.
 */
@Service
public class AgentToolService {

    private static final Logger log = LoggerFactory.getLogger(AgentToolService.class);
    private static final int SUMMARY_LIMIT_CHARS = 8_000;
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ToolRegistry registry;
    private final AgentStore store;
    private final AuditService audit;
    private final HejjeClock clock;
    private final ObjectMapper json;
    private final ObjectMapper outputJson;

    AgentToolService(ToolRegistry registry, AgentStore store, AuditService audit, HejjeClock clock, ObjectMapper json) {
        this.registry = registry;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
        this.outputJson = json.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public List<ToolDescriptor> catalog() {
        return registry.catalog();
    }

    /** The tools this credential may call. */
    public List<ToolDescriptor> catalogFor(HejjePrincipal principal) {
        return registry.catalog().stream().filter(d -> principal.hasScope(d.requiredScope())).toList();
    }

    public ToolResult invoke(String name, JsonNode input, ToolContext ctx) {
        long started = System.nanoTime();
        JsonNode args = input == null || input.isNull() || input.isMissingNode() ? json.createObjectNode() : input;
        Optional<ToolRegistry.Registered> found = registry.find(name);
        if (found.isEmpty()) {
            return finish(ctx, name, args, ToolStatus.UNKNOWN_TOOL, true, null, "Unknown tool " + name, List.of(), started);
        }
        AgentTool tool = found.get().tool();
        if (!ctx.principal().hasScope(tool.requiredScope())) {
            return finish(ctx, name, args, ToolStatus.FORBIDDEN, false, null, "Tool " + name + " requires scope " + tool.requiredScope(), List.of(), started);
        }
        List<String> errors = JsonSchemaLite.validate(tool.inputSchema(), args, "$");
        if (!errors.isEmpty()) {
            return finish(ctx, name, args, ToolStatus.INVALID_INPUT, true, null, "Invalid input for " + name, errors, started);
        }
        Object typed;
        try {
            typed = json.treeToValue(args, tool.inputType());
        } catch (JsonProcessingException e) {
            return finish(ctx, name, args, ToolStatus.INVALID_INPUT, true, null, "Invalid input for " + name, List.of(e.getOriginalMessage()), started);
        }
        Object out;
        try {
            out = tool.handler().handle(typed, ctx);
        } catch (ToolException e) {
            return finish(ctx, name, args, e.status(), true, null, e.getMessage(), List.of(), started);
        } catch (IllegalArgumentException e) {
            return finish(ctx, name, args, ToolStatus.INVALID_INPUT, true, null, e.getMessage(), List.of(), started);
        } catch (NoSuchElementException e) {
            return finish(ctx, name, args, ToolStatus.NOT_FOUND, true, null, e.getMessage(), List.of(), started);
        } catch (LlmException.Unavailable e) {
            return finish(ctx, name, args, ToolStatus.UNAVAILABLE, true, null, e.getMessage(), List.of(), started);
        } catch (RuntimeException e) {
            log.warn("Agent tool {} failed", name, e);
            return finish(ctx, name, args, ToolStatus.FAILED, true, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), List.of(), started);
        }
        JsonNode output = outputJson.valueToTree(out);
        List<String> outErrors = JsonSchemaLite.validate(found.get().descriptor().outputSchema(), output, "$");
        if (!outErrors.isEmpty()) {
            log.error("Agent tool {} produced output violating its schema: {}", name, outErrors);
            return finish(ctx, name, args, ToolStatus.INVALID_OUTPUT, true, null, "Tool output failed its schema", outErrors, started);
        }
        return finish(ctx, name, args, ToolStatus.OK, true, output, null, List.of(), started);
    }

    private ToolResult finish(ToolContext ctx, String tool, JsonNode input, ToolStatus status, boolean scopeOk, JsonNode output, String error,
            List<String> details, long startedNanos) {
        long latency = (System.nanoTime() - startedNanos) / 1_000_000;
        UUID actionId = Ids.newId();
        String correlation = CorrelationContext.get().map(Object::toString).orElse(null);
        JsonNode summary = output != null ? summarize(output) : errorSummary(error, details);
        store.insertAction(new AgentAction(actionId, ctx.sessionId(), tool, input, summary, scopeOk, status.name(), error, latency, correlation, clock.now()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool);
        payload.put("status", status.name());
        payload.put("scopeOk", scopeOk);
        payload.put("sessionId", ctx.sessionId().toString());
        payload.put("actionId", actionId.toString());
        payload.put("latencyMs", latency);
        if (error != null) {
            payload.put("error", error);
        }
        AuditEvent event = AuditEvent.of(AuditEventType.AGENT_TOOL_CALLED, ActorType.AGENT).withActorId(ctx.principal().name())
                .withClientSource(ctx.clientSource()).withPayload(payload);
        audit.record(CorrelationContext.get().map(event::withCorrelationId).orElse(event));
        return new ToolResult(tool, status, output, error, details, actionId, ctx.sessionId(), latency);
    }

    /** The output itself when small, else its size and the ids it mentions (so a session can still be traced). */
    private JsonNode summarize(JsonNode output) {
        String text = output.toString();
        if (text.length() <= SUMMARY_LIMIT_CHARS) {
            return output;
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = UUID_TEXT.matcher(text);
        while (m.find() && ids.size() < 100) {
            ids.add(m.group());
        }
        ObjectNode s = json.createObjectNode().put("truncated", true).put("chars", text.length());
        s.putArray("ids").addAll(ids.stream().map(json.getNodeFactory()::textNode).toList());
        return s;
    }

    private JsonNode errorSummary(String error, List<String> details) {
        ObjectNode s = json.createObjectNode().put("error", error);
        if (!details.isEmpty()) {
            s.putArray("details").addAll(details.stream().map(json.getNodeFactory()::textNode).toList());
        }
        return s;
    }

    // ---- sessions

    public AgentSession startSession(HejjePrincipal principal, String profile, String purpose) {
        AgentSession s = new AgentSession(Ids.newId(), principal.type() == HejjePrincipal.Type.CLIENT ? principal.id() : null, principal.type().name(),
                principal.id(), principal.name(), profile, purpose, clock.now(), null);
        store.insertSession(s);
        return s;
    }

    public void endSession(UUID sessionId) {
        store.endSession(sessionId, clock.now());
    }

    /** The caller's open session for {@code purpose} started today (IST), or a new one. Used by direct and MCP invocations. */
    public AgentSession sessionFor(HejjePrincipal principal, String purpose) {
        Instant dayStart = clock.today().atStartOfDay(clock.zone()).toInstant();
        return store.openSession(principal.id(), purpose, dayStart).orElseGet(() -> startSession(principal, null, purpose));
    }

    public Optional<AgentSession> session(UUID id) {
        return store.findSession(id);
    }

    /** Whether {@code principal} may see or use this session: its own, or any for admin. */
    public boolean canAccess(HejjePrincipal principal, AgentSession session) {
        return principal.hasScope(ScopeCatalog.ADMIN) || principal.id().equals(session.principalId());
    }

    /** Recent sessions: all for admin, else the caller's own. */
    public List<AgentSession> sessions(HejjePrincipal viewer, int limit) {
        return store.sessions(viewer.hasScope(ScopeCatalog.ADMIN) ? null : viewer.id(), Math.max(1, Math.min(limit, 200)));
    }

    public List<AgentAction> actions(UUID sessionId) {
        return new ArrayList<>(store.actions(sessionId));
    }
}
