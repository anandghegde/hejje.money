package money.hejje.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import money.hejje.common.security.ScopeCatalog;

/**
 * One agent capability (PRD 28): a name, a description the model reads, the scope the caller's credential must hold,
 * the JSON schema of its input, the typed input/output records, and the handler. {@code transactional} tools create
 * intents or approvals; everything else is read-only. The output schema is generated from {@code outputType}.
 */
public record AgentTool(String name, String description, String requiredScope, boolean transactional, JsonNode inputSchema, Class<?> inputType,
        Class<?> outputType, ToolHandler handler) {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    public AgentTool {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Tool name must be snake_case: " + name);
        }
        if (!ScopeCatalog.isValid(requiredScope)) {
            throw new IllegalArgumentException("Unknown scope " + requiredScope + " for tool " + name);
        }
        if (description == null || description.isBlank() || inputSchema == null || inputType == null || outputType == null || handler == null) {
            throw new IllegalArgumentException("Tool " + name + " is incomplete");
        }
    }

    /** A read-only tool with a typed handler. */
    public static <I, O> AgentTool of(String name, String description, String requiredScope, JsonNode inputSchema, Class<I> inputType, Class<O> outputType,
            BiFunction<I, ToolContext, O> handler) {
        return new AgentTool(name, description, requiredScope, false, inputSchema, inputType, outputType, (in, ctx) -> handler.apply(inputType.cast(in), ctx));
    }

    /** A transactional tool (creates intents or approvals) with a typed handler. */
    public static <I, O> AgentTool transactional(String name, String description, String requiredScope, JsonNode inputSchema, Class<I> inputType,
            Class<O> outputType, BiFunction<I, ToolContext, O> handler) {
        return new AgentTool(name, description, requiredScope, true, inputSchema, inputType, outputType, (in, ctx) -> handler.apply(inputType.cast(in), ctx));
    }
}
