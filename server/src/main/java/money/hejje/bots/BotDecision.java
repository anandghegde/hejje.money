package money.hejje.bots;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ExecutionMode;

/** One decision of a bot at a decision point and what Hejje did with it (plan M7.3, table {@code bot_decision}). */
public record BotDecision(UUID id, UUID botId, String pointId, String instrument, Action action, BigDecimal stop, BigDecimal target, Double confidence,
        String thesis, String stage, Map<String, Object> scores, List<Object> candidates, Long latencyMs, Outcome outcome, String detail, UUID signalId,
        UUID orderId, ExecutionMode mode, Instant decidedAt) {

    public enum Action { ENTER_LONG, ENTER_SHORT, EXIT, TAKE_PROFIT, MOVE_STOP, HOLD, NONE, SKIPPED }

    /**
     * EXECUTED: the entry went to the broker (SIM, PAPER, AUTO allowed); APPROVAL: an approval waits (CONFIRM, or AUTO
     * held); EXITING: the position is being closed; MOVED: the stop was tightened; NOTED: HOLD/NONE; REFUSED: invalid or
     * refused (the detail says why); SKIPPED: no answer before the timeout.
     */
    public enum Outcome { EXECUTED, APPROVAL, EXITING, MOVED, NOTED, REFUSED, SKIPPED }

    /** A decision as a bot sends it. {@code stop} is required for entries; quantity is always Hejje's. */
    public record Input(String instrument, String action, BigDecimal stop, BigDecimal target, Double confidence, String thesis, String stage,
            Map<String, Object> scores, List<Object> candidates) {}

    /**
     * A bot's answer to one decision point (WebSocket reply or {@code POST /bots/{id}/decisions}); {@code usage} is what an
     * LLM bot spent on it (optional, shown in the harness, plan M7.4).
     */
    public record Reply(String pointId, List<Input> decisions, Usage usage) {

        public Reply {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }

        public Reply(String pointId, List<Input> decisions) {
            this(pointId, decisions, null);
        }
    }

    /** Tokens and cost an LLM bot reports for one answer. */
    public record Usage(Long inputTokens, Long outputTokens, BigDecimal costRupees) {}
}
