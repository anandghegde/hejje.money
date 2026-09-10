package money.hejje.llm;

/** Aggregated {@code llm_call} rows over a period: attempts, failed attempts, tokens and estimated cost. */
public record LlmUsage(long calls, long failed, long inputTokens, long outputTokens, long costPaise) {

    public static final LlmUsage NONE = new LlmUsage(0, 0, 0, 0, 0);
}
