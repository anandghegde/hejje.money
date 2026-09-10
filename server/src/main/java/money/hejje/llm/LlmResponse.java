package money.hejje.llm;

import java.util.List;

/** A completion: the text and/or requested tool calls, plus what it cost. */
public record LlmResponse(String text, String provider, String model, Integer inputTokens, Integer outputTokens, long latencyMs,
        List<LlmToolCall> toolCalls, String finishReason) {

    public LlmResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public LlmResponse(String text, String provider, String model, Integer inputTokens, Integer outputTokens, long latencyMs) {
        this(text, provider, model, inputTokens, outputTokens, latencyMs, List.of(), null);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
