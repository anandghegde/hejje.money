package money.hejje.llm;

/** A completion: the text plus what it cost. */
public record LlmResponse(String text, String provider, String model, Integer inputTokens, Integer outputTokens, long latencyMs) {
}
