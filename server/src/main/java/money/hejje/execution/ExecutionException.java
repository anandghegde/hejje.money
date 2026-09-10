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

        /** The failed checks as "name (observed vs limit): message", for progress details (baskets, splits). */
        public String failures() {
            return checks.stream().filter(c -> !c.passed()).map(c -> c.name() + (c.observed() == null ? "" : " (" + c.observed()
                    + (c.limit() == null ? "" : " vs " + c.limit()) + ")") + ": " + c.message()).collect(java.util.stream.Collectors.joining("; "));
        }
    }

    /** Why an execution attempt was refused, with the failed risk checks when there are any. */
    public static String describe(RuntimeException e) {
        return e instanceof RiskRejected r && !r.failures().isEmpty() ? "Risk rejected: " + r.failures() : e.getMessage();
    }

    /** This instance does not hold the executor lease (a standby, or it lost the lease): nothing was sent to the broker; 503. */
    public static final class NotActiveExecutor extends ExecutionException {
        public NotActiveExecutor(String message) { super(message); }
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
