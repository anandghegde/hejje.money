package money.hejje.bots;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Timeframe;

/**
 * A registered bot (plan M7.3).
 *
 * @param strategyId           the backing strategy: generated (family bot) for EXTERNAL and LLM bots, an existing one for
 *                             STRATEGY bots
 * @param decisionEveryMinutes decision points every N minutes instead of every closed bar of {@code timeframe} (null)
 * @param knowledgeCutoff      an LLM or Jev bot's training cutoff: a session dated before it is flagged (the model may know the day)
 * @param exitConfirmVotes     consecutive decision points that must vote EXIT / TAKE_PROFIT for the same position before it
 *                             is closed (plan M9.5; 1 = at once)
 * @param questionSet          a JEV bot's question set prefix ({@code bot} → bot-stage1, bot-stage2, bot-position); null otherwise
 */
public record Bot(UUID id, String name, String version, Kind kind, LocalDate knowledgeCutoff, Set<ExecutionMode> allowedModes, UUID strategyId,
        Timeframe timeframe, Integer decisionEveryMinutes, List<String> universe, boolean enabled, String createdBy, Instant createdAt, Instant updatedAt,
        int exitConfirmVotes, String questionSet) {

    /** EXTERNAL and LLM bots connect over the protocol; STRATEGY bots run a strategy; JEV bots run in-process on Jev (M9.5). */
    public enum Kind { EXTERNAL, STRATEGY, LLM, JEV }

    public Bot {
        allowedModes = Set.copyOf(allowedModes);
        universe = List.copyOf(universe);
        exitConfirmVotes = Math.max(1, exitConfirmVotes);
    }

    public Bot(UUID id, String name, String version, Kind kind, LocalDate knowledgeCutoff, Set<ExecutionMode> allowedModes, UUID strategyId,
            Timeframe timeframe, Integer decisionEveryMinutes, List<String> universe, boolean enabled, String createdBy, Instant createdAt, Instant updatedAt) {
        this(id, name, version, kind, knowledgeCutoff, allowedModes, strategyId, timeframe, decisionEveryMinutes, universe, enabled, createdBy, createdAt,
                updatedAt, 1, null);
    }
}
