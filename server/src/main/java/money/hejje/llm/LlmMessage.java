package money.hejje.llm;

import java.util.List;

/**
 * One conversation turn in provider-neutral form (the system prompt travels separately on {@link LlmRequest}).
 * Assistant turns may carry tool calls; a tool turn answers one call by id and tool name.
 */
public record LlmMessage(Role role, String content, List<LlmToolCall> toolCalls, String toolCallId, String toolName) {

    public enum Role { USER, ASSISTANT, TOOL }

    public LlmMessage {
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content, List.of(), null, null);
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, content, toolCalls, null, null);
    }

    public static LlmMessage tool(String toolCallId, String toolName, String content) {
        return new LlmMessage(Role.TOOL, content, List.of(), toolCallId, toolName);
    }
}
