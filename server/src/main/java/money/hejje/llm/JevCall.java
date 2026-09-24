package money.hejje.llm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One recorded Jev call ({@code jev_call}) with its answers. {@code costPaise} is an estimate and may be fractional. */
public record JevCall(UUID id, Instant at, String purpose, String subject, String setName, String setVersion, String model, String stateHash,
        long latencyMs, Integer inputTokens, BigDecimal costPaise, String outcome, String error, List<JevAnswer> answers) {

    public JevCall {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }
}
