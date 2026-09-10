package money.hejje.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import money.hejje.agent.ToolDescriptor;

/** Renders docs/agent-tools.md from the registry (kept in sync by {@code AgentToolsIT}; regenerate with HEJJE_REGEN_DOCS=1). */
public final class AgentToolDocs {

    private AgentToolDocs() {
    }

    public static String render(List<ToolDescriptor> catalog, ObjectMapper json) {
        StringBuilder md = new StringBuilder();
        md.append("# Agent tools\n\n");
        md.append("<!-- Generated from the tool registry by AgentToolsIT (HEJJE_REGEN_DOCS=1 ./gradlew test --tests '*AgentToolsIT*'). Do not edit. -->\n\n");
        md.append("Every agent capability is a typed tool (PRD 28). The scope is checked against the caller's credential, input is validated\n");
        md.append("against the schema below, output against the generated output schema, and every call is recorded in `agent_action`\n");
        md.append("with an `AGENT_TOOL_CALLED` audit event. See `docs/agents.md` for sessions, presets, the REST and MCP endpoints.\n\n");
        md.append("| Tool | Scope | Kind | Description |\n|---|---|---|---|\n");
        for (ToolDescriptor d : catalog) {
            md.append("| `").append(d.name()).append("` | `").append(d.requiredScope()).append("` | ").append(d.transactional() ? "transactional" : "read")
                    .append(" | ").append(d.description().replace("|", "\\|").replace("\n", " ")).append(" |\n");
        }
        for (ToolDescriptor d : catalog) {
            md.append("\n## `").append(d.name()).append("`\n\n");
            md.append(d.description()).append("\n\n");
            md.append("Scope `").append(d.requiredScope()).append("`").append(d.transactional() ? ", transactional" : ", read-only").append(".\n\n");
            md.append("Input schema:\n\n```json\n").append(pretty(json, d.inputSchema())).append("\n```\n\n");
            md.append("Output schema:\n\n```json\n").append(pretty(json, d.outputSchema())).append("\n```\n");
        }
        return md.toString();
    }

    private static String pretty(ObjectMapper json, Object node) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
