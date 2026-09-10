package money.hejje.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.agent.AgentAction;
import money.hejje.agent.AgentSession;
import money.hejje.agent.AgentToolService;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolDescriptor;
import money.hejje.agent.ToolResult;
import money.hejje.common.security.HejjePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tool catalog, direct tool invocation for external agents (the caller's own credential), and session traces. */
@RestController
@RequestMapping("/api/v1/agents")
@PreAuthorize("isAuthenticated()")
class AgentController {

    static final URI TOOL_PROBLEM = URI.create("https://hejje.money/problems/agent-tool");

    record SessionView(AgentSession session, List<AgentAction> actions) {}

    private final AgentToolService tools;

    AgentController(AgentToolService tools) {
        this.tools = tools;
    }

    @GetMapping("/tools")
    List<ToolDescriptor> catalog() {
        return tools.catalog();
    }

    /**
     * Invokes one tool. {@code X-Agent-Session} selects one of the caller's sessions (default: the caller's "direct"
     * session for today); {@code Idempotency-Key} is passed to transactional tools.
     */
    @PostMapping("/tools/{name}")
    ResponseEntity<?> invoke(@PathVariable String name, @RequestBody(required = false) JsonNode input,
            @RequestHeader(name = "X-Agent-Session", required = false) UUID sessionId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal HejjePrincipal principal) {
        AgentSession session = sessionId == null ? tools.sessionFor(principal, "direct")
                : tools.session(sessionId).filter(s -> tools.canAccess(principal, s) && s.endedAt() == null)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown or closed agent session " + sessionId));
        ToolResult result = tools.invoke(name, input, new ToolContext(principal, session.id(), idempotencyKey, "agent-api"));
        if (result.ok()) {
            return ResponseEntity.ok(result);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(result.status().httpStatus()), result.error());
        problem.setType(TOOL_PROBLEM);
        problem.setTitle(result.status().name());
        problem.setProperty("tool", result.tool());
        problem.setProperty("toolStatus", result.status().name());
        problem.setProperty("errors", result.details());
        problem.setProperty("actionId", result.actionId());
        problem.setProperty("sessionId", result.sessionId());
        return ResponseEntity.status(result.status().httpStatus()).body(problem);
    }

    @GetMapping("/sessions")
    List<AgentSession> sessions(@RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal HejjePrincipal principal) {
        return tools.sessions(principal, limit);
    }

    @GetMapping("/sessions/{id}")
    ResponseEntity<SessionView> session(@PathVariable UUID id, @AuthenticationPrincipal HejjePrincipal principal) {
        return tools.session(id).filter(s -> tools.canAccess(principal, s))
                .map(s -> ResponseEntity.ok(new SessionView(s, tools.actions(id))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }
}
