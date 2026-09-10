package money.hejje.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** The result of one tool call: output (when OK) or error with details, plus the recorded action id. */
public record ToolResult(String tool, ToolStatus status, JsonNode output, String error, List<String> details, UUID actionId, UUID sessionId, long latencyMs) {

    public ToolResult {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public boolean ok() {
        return status == ToolStatus.OK;
    }
}
