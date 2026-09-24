package money.hejje.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The outcome of one {@link JevService#evaluate} call. Never an exception: when {@code ok} is false the answers are
 * empty and {@code outcome} with {@code error} say why, so a caller falls back to its no-Jev behaviour.
 *
 * @param callId the {@code jev_call} row (null when disabled: nothing is recorded then)
 */
public record JevResult(boolean ok, Outcome outcome, Map<String, JevAnswer> answers, long latencyMs, Integer inputTokens, String model, UUID callId,
        String error) {

    public enum Outcome {
        /** Answered by the API. */
        OK,
        /** Answered from the store (SIM cache). */
        CACHED,
        /** The API refused or failed (after the retry, when one fitted in the deadline). */
        FAILED,
        /** No answer within the deadline. */
        TIMEOUT,
        /** The daily cost cap is reached. */
        BUDGET,
        /** The circuit is open after repeated failures. */
        CIRCUIT_OPEN,
        /** {@code hejje.jev.enabled=false}. */
        DISABLED
    }

    public JevResult {
        answers = answers == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(answers));
    }

    static JevResult failed(Outcome outcome, long latencyMs, UUID callId, String error) {
        return new JevResult(false, outcome, Map.of(), latencyMs, null, null, callId, error);
    }

    public Optional<JevAnswer> answer(String key) {
        return Optional.ofNullable(answers.get(key));
    }

    /** The noul of {@code key}, or {@code fallback} when absent. */
    public double noul(String key, double fallback) {
        JevAnswer a = answers.get(key);
        return a == null || a.noul() == null ? fallback : a.noul();
    }
}
