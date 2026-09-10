package money.hejje.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import money.hejje.agent.AgentSession;
import money.hejje.agent.AgentToolService;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolDescriptor;
import money.hejje.agent.ToolResult;
import money.hejje.common.security.HejjePrincipal;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal MCP server over streamable HTTP ({@code POST /mcp}, JSON responses, no server-initiated stream) exposing the
 * tool registry to external agents such as Claude Code with a scoped Hejje API key. Methods: {@code initialize},
 * {@code notifications/initialized}, {@code ping}, {@code tools/list} (only tools the key may call), {@code tools/call}.
 * Calls go through {@link AgentToolService}, so scope checks, validation and audit are identical to the REST path.
 */
@RestController
class McpController {

    static final String PROTOCOL_VERSION = "2025-06-18";

    private final AgentToolService tools;
    private final ObjectMapper json;
    private final String version;

    McpController(AgentToolService tools, ObjectMapper json, ObjectProvider<BuildProperties> build) {
        this.tools = tools;
        this.json = json;
        BuildProperties b = build.getIfAvailable();
        this.version = b == null ? "dev" : b.getVersion();
    }

    @GetMapping("/mcp")
    ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @PostMapping(path = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> handle(@RequestBody JsonNode message, @AuthenticationPrincipal HejjePrincipal principal) {
        if (message.isArray()) {
            return ResponseEntity.badRequest().body(error(null, -32600, "Batching is not supported"));
        }
        JsonNode id = message.get("id");
        String method = message.path("method").asText("");
        if (id == null || id.isNull()) {
            return ResponseEntity.accepted().build(); // notifications (initialized, cancelled) need no answer
        }
        return ResponseEntity.ok(switch (method) {
            case "initialize" -> result(id, initialize(message.path("params")));
            case "ping" -> result(id, json.createObjectNode());
            case "tools/list" -> result(id, list(principal));
            case "tools/call" -> call(id, message.path("params"), principal);
            default -> error(id, -32601, "Method not found: " + method);
        });
    }

    private ObjectNode initialize(JsonNode params) {
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", params.path("protocolVersion").asText(PROTOCOL_VERSION));
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        r.putObject("serverInfo").put("name", "hejje").put("version", version);
        r.put("instructions", "Hejje trading platform tools. Read tools return compact JSON with ids you can cite; transactional tools only create "
                + "proposals that a human approves. Every call is scoped by your API key and audited.");
        return r;
    }

    private ObjectNode list(HejjePrincipal principal) {
        ObjectNode r = json.createObjectNode();
        ArrayNode list = r.putArray("tools");
        for (ToolDescriptor d : tools.catalogFor(principal)) {
            ObjectNode t = list.addObject().put("name", d.name()).put("description", d.description());
            t.set("inputSchema", d.inputSchema());
            t.set("outputSchema", d.outputSchema());
            t.putObject("annotations").put("readOnlyHint", !d.transactional());
        }
        return r;
    }

    private ObjectNode call(JsonNode id, JsonNode params, HejjePrincipal principal) {
        String name = params.path("name").asText(null);
        if (name == null) {
            return error(id, -32602, "params.name is required");
        }
        AgentSession session = tools.sessionFor(principal, "mcp");
        ToolResult result = tools.invoke(name, params.get("arguments"), new ToolContext(principal, session.id(), null, "mcp"));
        ObjectNode r = json.createObjectNode();
        ArrayNode content = r.putArray("content");
        if (result.ok()) {
            content.addObject().put("type", "text").put("text", result.output().toString());
            r.set("structuredContent", result.output());
            r.put("isError", false);
        } else {
            String details = result.details().isEmpty() ? "" : " " + String.join("; ", result.details());
            content.addObject().put("type", "text").put("text", result.status() + ": " + result.error() + details);
            r.put("isError", true);
        }
        return result(id, r);
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = json.createObjectNode().put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = json.createObjectNode().put("jsonrpc", "2.0");
        r.set("id", id);
        r.putObject("error").put("code", code).put("message", message);
        return r;
    }
}
