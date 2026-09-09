package money.hejje.llm;

/** One streamed piece of a completion. */
public record LlmChunk(String delta, boolean last) {
}
