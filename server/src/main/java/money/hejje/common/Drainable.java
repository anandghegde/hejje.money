package money.hejje.common;

/**
 * A component that hands work to its own thread (plan M7.2). The SIM replay calls {@link #drain()} on every one after each
 * replayed tick, so a step has fully settled (signals, AUTO decisions, fills) before the next begins and the result does
 * not depend on replay speed.
 */
public interface Drainable {

    /** Blocks until every task submitted so far has finished. */
    void drain();
}
