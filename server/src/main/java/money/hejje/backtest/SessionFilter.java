package money.hejje.backtest;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.Side;

/**
 * A research-only restriction of entries per session (plan M8.8): which instruments may be entered and on which side, as
 * supplied by a validation script from stored snapshots (the RS leaders of the previous session, the market condition).
 * It only ever blocks entries; exits are untouched. A run that carries a filter is never the backtest a strategy
 * version is judged by.
 *
 * @param days          per session date; a rule's null {@code instruments} or {@code sides} means no restriction on that axis
 * @param unlistedBlock what a session without a rule does: true blocks every entry, false leaves the session unrestricted
 */
public record SessionFilter(Map<LocalDate, Rule> days, boolean unlistedBlock) {

    public record Rule(Set<UUID> instruments, Set<Side> sides) {
    }

    public SessionFilter {
        days = days == null ? Map.of() : Map.copyOf(days);
    }

    public boolean allows(LocalDate session, UUID instrumentId, Side side) {
        Rule rule = days.get(session);
        if (rule == null) {
            return !unlistedBlock;
        }
        return (rule.instruments() == null || rule.instruments().contains(instrumentId)) && (rule.sides() == null || rule.sides().contains(side));
    }
}
