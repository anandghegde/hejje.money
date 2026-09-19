package money.hejje.common;

/** Global execution mode of the server process. Exactly one mode per process. */
public enum ExecutionMode {
    /** Simulated fills, no broker orders. */
    PAPER,
    /** Real orders, every order needs human confirmation. */
    CONFIRM,
    /** Real orders, policy-eligible strategies execute without confirmation. */
    AUTO,
    /**
     * Historical replay on a simulation clock with simulated fills (plan M7.1): a separate instance with its own database,
     * dev or {@code sim} profile only, never a real broker adapter.
     */
    SIM;

    /** True when fills are simulated (PAPER, SIM): no order reaches a broker. */
    public boolean simulated() {
        return this == PAPER || this == SIM;
    }
}
