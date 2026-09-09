package money.hejje.strategy;

import java.util.List;

/** A definition failed structural or semantic validation. Maps to a 400 problem with {@code errors}. */
public class StrategyValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public StrategyValidationException(List<ValidationError> errors) {
        super(errors.isEmpty() ? "Invalid strategy definition"
                : errors.size() + " definition error(s): " + errors.get(0).path() + ": " + errors.get(0).message());
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }
}
