package money.hejje.llm;

/** One streamed piece of a completion. The last chunk carries the complete {@link LlmResponse} (text, tool calls, usage). */
public record LlmChunk(String delta, boolean last, LlmResponse response) {

    public LlmChunk(String delta, boolean last) {
        this(delta, last, null);
    }
}
