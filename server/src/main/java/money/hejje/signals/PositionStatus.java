package money.hejje.signals;

/** State of a strategy-managed position. */
public enum PositionStatus {
    PENDING_ENTRY, OPEN, EXITING, CLOSED;

    public boolean isLive() {
        return this != CLOSED;
    }
}
