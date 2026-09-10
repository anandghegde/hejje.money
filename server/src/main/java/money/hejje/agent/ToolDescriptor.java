package money.hejje.agent;

import com.fasterxml.jackson.databind.JsonNode;

/** A catalog entry: what {@code GET /agents/tools}, MCP {@code tools/list} and docs/agent-tools.md show. */
public record ToolDescriptor(String name, String description, String requiredScope, boolean transactional, JsonNode inputSchema, JsonNode outputSchema) {
}
