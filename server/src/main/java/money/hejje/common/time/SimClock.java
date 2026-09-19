package money.hejje.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The simulation clock of a SIM instance (plan M7.1): a {@link Clock} that stands still until the replay sets or
 * advances it. While paused it refuses to advance, so a paused replay can never move time by accident. Thread-safe;
 * {@link HejjeClock} wraps it like the system clock, so every business component reads simulation time.
 */
public final class SimClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;
    private volatile boolean paused;

    public SimClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    /** Moves to {@code at}, forwards or backwards (a new session starts earlier than the last one ended). */
    public synchronized void set(Instant at) {
        instant = at;
    }

    /** Moves forwards by {@code step}; refused while paused or for a negative step. */
    public synchronized Instant advance(Duration step) {
        if (step.isNegative()) {
            throw new IllegalArgumentException("The simulation clock only moves forwards; use set() to start another session");
        }
        if (paused) {
            throw new IllegalStateException("The simulation clock is paused");
        }
        instant = instant.plus(step);
        return instant;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean paused() {
        return paused;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** A clock fixed at this clock's current instant in {@code zone} (java.time contract: a view, not a new simulation). */
    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }
}
