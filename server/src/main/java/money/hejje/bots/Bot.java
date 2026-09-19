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
 * @param knowledgeCutoff      an LLM bot's training cutoff: a session dated before it is flagged (the model may know the day)
 */
public record Bot(UUID id, String name, String version, Kind kind, LocalDate knowledgeCutoff, Set<ExecutionMode> allowedModes, UUID strategyId,
        Timeframe timeframe, Integer decisionEveryMinutes, List<String> universe, boolean enabled, String createdBy, Instant createdAt, Instant updatedAt) {

    public enum Kind { EXTERNAL, STRATEGY, LLM }

    public Bot {
        allowedModes = Set.copyOf(allowedModes);
        universe = List.copyOf(universe);
    }
}
