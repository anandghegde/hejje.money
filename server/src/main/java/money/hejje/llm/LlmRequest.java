package money.hejje.llm;

/**
 * One completion request. {@code purpose} and {@code promptVersion} are logged so prompts are auditable;
 * {@code jsonMode} asks the provider for a JSON object answer where supported.
 */
public record LlmRequest(String profile, String purpose, String promptVersion, String systemPrompt, String userPrompt, Integer maxTokens, Double temperature,
        boolean jsonMode) {

    public LlmRequest {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("profile is required");
        }
        if (userPrompt == null) {
            throw new IllegalArgumentException("userPrompt is required");
        }
        purpose = purpose == null ? "unspecified" : purpose;
        promptVersion = promptVersion == null ? "unversioned" : promptVersion;
    }
}
