package money.hejje.signals;

public class SignalException extends RuntimeException {

    protected SignalException(String message) {
        super(message);
    }

    public static class NotFound extends SignalException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** The signal is no longer actionable (expired, executed, skipped) or the deployment cannot trade. */
    public static class NotActionable extends SignalException {
        public NotActionable(String message) {
            super(message);
        }
    }
}
