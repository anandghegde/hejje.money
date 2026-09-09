package money.hejje.strategy;

import java.util.List;

/** Result of {@code POST /strategies/validate}: the parsed definition when valid, otherwise the structured errors. */
public record ValidationReport(boolean valid, List<ValidationError> errors, StrategyDefinition definition) {
    public ValidationReport {
        errors = List.copyOf(errors);
    }
}
