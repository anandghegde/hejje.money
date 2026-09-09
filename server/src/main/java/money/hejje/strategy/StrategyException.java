package money.hejje.strategy;

/** Strategy module failures that map to HTTP statuses: {@link NotFound} (404) and {@link Conflict} (409). */
public class StrategyException extends RuntimeException {

    protected StrategyException(String message) {
        super(message);
    }

    public static class NotFound extends StrategyException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** An operation that contradicts current state: illegal lifecycle transition, duplicate name, identical version. */
    public static class Conflict extends StrategyException {
        public Conflict(String message) {
            super(message);
        }
    }
}
