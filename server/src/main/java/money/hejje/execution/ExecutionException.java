package money.hejje.execution;

import java.util.List;
import money.hejje.risk.RiskCheck;

/** Pipeline rejections mapped to HTTP problem responses by the controller. */
public sealed class ExecutionException extends RuntimeException {

    ExecutionException(String message) {
        super(message);
    }

    /** Validation failed before risk/adapter; 422 with the list of reasons. Adapter was never called. */
    public static final class Validation extends ExecutionException {
        private final List<String> reasons;
        public Validation(List<String> reasons) {
            super("Validation failed: " + reasons);
            this.reasons = List.copyOf(reasons);
        }
        public List<String> reasons() { return reasons; }
    }

    /** Risk rejected the intent; 422 with the check list. */
    public static final class RiskRejected extends ExecutionException {
        private final List<RiskCheck> checks;
        public RiskRejected(List<RiskCheck> checks) {
            super("Risk rejected");
            this.checks = List.copyOf(checks);
        }
        public List<RiskCheck> checks() { return checks; }
    }

    /** Same idempotency key is still in flight; 409. */
    public static final class IdempotencyInFlight extends ExecutionException {
        public IdempotencyInFlight() { super("A request with this Idempotency-Key is still in flight"); }
    }

    /** Same idempotency key, different body; 422. */
    public static final class IdempotencyMismatch extends ExecutionException {
        public IdempotencyMismatch() { super("Idempotency-Key was used with a different request body"); }
    }
}
