package money.hejje.system;

/**
 * One named precondition for live execution. Beans implementing this are collected by {@link ExecutionReadiness}.
 * Later phases add broker session, market data and reconciliation checks.
 */
public interface ReadinessCheck {

    /** Stable machine name, for example {@code staticIp}. */
    String name();

    CheckResult result();

    /** A check outcome. {@code OK} and {@code SKIPPED} allow execution; {@code BLOCKING} prevents it. */
    record CheckResult(Status status, String detail) {

        public enum Status { OK, BLOCKING, SKIPPED }

        public static CheckResult ok(String detail) {
            return new CheckResult(Status.OK, detail);
        }

        public static CheckResult blocking(String detail) {
            return new CheckResult(Status.BLOCKING, detail);
        }

        public static CheckResult skipped(String detail) {
            return new CheckResult(Status.SKIPPED, detail);
        }

        public boolean allowsExecution() {
            return status != Status.BLOCKING;
        }
    }
}
