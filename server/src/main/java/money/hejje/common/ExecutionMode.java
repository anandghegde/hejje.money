package money.hejje.common;

/** Global execution mode of the server process. Exactly one mode per process. */
public enum ExecutionMode {
    /** Simulated fills, no broker orders. */
    PAPER,
    /** Real orders, every order needs human confirmation. */
    CONFIRM,
    /** Real orders, policy-eligible strategies execute without confirmation. */
    AUTO
}
