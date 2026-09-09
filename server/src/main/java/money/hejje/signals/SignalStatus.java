package money.hejje.signals;

/** Lifecycle of a signal. ACTIVE and PREPARED are actionable; the rest are terminal. */
public enum SignalStatus {
    ACTIVE, PREPARED, EXECUTED, EXPIRED, SKIPPED, BLOCKED;

    public boolean isActionable() {
        return this == ACTIVE || this == PREPARED;
    }
}
