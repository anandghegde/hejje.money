package money.hejje.llm;

/** A provider or validation failure. {@code retryable} drives the retry loop. */
public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** The LLM is disabled, no profile/provider is configured, the provider's circuit is open or the budget is spent. */
    public static class Unavailable extends LlmException {
        public Unavailable(String message) {
            super(message, false);
        }
    }

    /** Today's estimated spend reached {@code hejje.llm.daily-cost-cap}; calls resume the next IST day. */
    public static class BudgetExceeded extends Unavailable {
        public BudgetExceeded(String message) {
            super(message);
        }
    }
}
