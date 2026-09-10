package money.hejje.llm;

import java.util.List;

/**
 * One completion request. {@code purpose} and {@code promptVersion} are logged so prompts are auditable;
 * {@code jsonMode} asks the provider for a JSON object answer where supported.
 * <p>
 * A single-turn request sets {@code userPrompt}; a conversation sets {@code messages} (the turns after the system prompt,
 * including assistant tool calls and tool results) and optionally {@code tools} the model may call. For a conversation
 * {@code userPrompt} defaults to the last user message (the fixture provider matches on it).
 */
public record LlmRequest(String profile, String purpose, String promptVersion, String systemPrompt, String userPrompt, Integer maxTokens, Double temperature,
        boolean jsonMode, List<LlmMessage> messages, List<LlmTool> tools) {

    public LlmRequest {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("profile is required");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (userPrompt == null) {
            userPrompt = messages.stream().filter(m -> m.role() == LlmMessage.Role.USER).reduce((a, b) -> b).map(LlmMessage::content).orElse(null);
        }
        if (userPrompt == null) {
            throw new IllegalArgumentException("userPrompt or a user message is required");
        }
        purpose = purpose == null ? "unspecified" : purpose;
        promptVersion = promptVersion == null ? "unversioned" : promptVersion;
    }

    public LlmRequest(String profile, String purpose, String promptVersion, String systemPrompt, String userPrompt, Integer maxTokens, Double temperature,
            boolean jsonMode) {
        this(profile, purpose, promptVersion, systemPrompt, userPrompt, maxTokens, temperature, jsonMode, List.of(), List.of());
    }

    /** A multi-turn request with tools. */
    public static LlmRequest chat(String profile, String purpose, String promptVersion, String systemPrompt, List<LlmMessage> messages, List<LlmTool> tools) {
        return new LlmRequest(profile, purpose, promptVersion, systemPrompt, null, null, null, false, messages, tools);
    }

    /** The turns after the system prompt: the explicit messages, or the single user prompt. */
    public List<LlmMessage> conversation() {
        return messages.isEmpty() ? List.of(LlmMessage.user(userPrompt)) : messages;
    }

    /** The text hashed for the call log: the user prompt, or the whole conversation plus tool names. */
    public String promptText() {
        if (messages.isEmpty() && tools.isEmpty()) {
            return userPrompt;
        }
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : conversation()) {
            sb.append(m.role()).append(':').append(m.content() == null ? "" : m.content());
            for (LlmToolCall c : m.toolCalls()) {
                sb.append(" call ").append(c.name()).append(c.arguments());
            }
            sb.append('\n');
        }
        for (LlmTool t : tools) {
            sb.append("tool:").append(t.name()).append('\n');
        }
        return sb.toString();
    }

    public LlmRequest withProfile(String newProfile) {
        return new LlmRequest(newProfile, purpose, promptVersion, systemPrompt, userPrompt, maxTokens, temperature, jsonMode, messages, tools);
    }

    public LlmRequest withDefaults(Integer defaultMaxTokens, Double defaultTemperature) {
        return new LlmRequest(profile, purpose, promptVersion, systemPrompt, userPrompt, maxTokens != null ? maxTokens : defaultMaxTokens,
                temperature != null ? temperature : defaultTemperature, jsonMode, messages, tools);
    }
}
