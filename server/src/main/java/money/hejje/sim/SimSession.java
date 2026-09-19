package money.hejje.sim;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import money.hejje.common.Money;

/**
 * One replay session (plan M7.2). {@code step} counts the replayed minutes of the current day (0..{@link #STEPS_PER_DAY});
 * {@code resultHash} is SHA-256 over the session's fills (time, instrument, side, quantity, price), so two runs of the same
 * session with the same decisions can be compared; {@code friction} is the transaction costs paid.
 */
public record SimSession(UUID id, SimSessionSpec spec, State state, Speed speed, int dayIndex, int days, LocalDate sessionDate, int step,
        int fills, Money friction, Money netPnl, String resultHash, String error, String createdBy, Instant createdAt, Instant finishedAt,
        Instant updatedAt, java.util.List<String> warnings) {

    public SimSession {
        warnings = warnings == null ? java.util.List.of() : java.util.List.copyOf(warnings);
    }

    public static final int STEPS_PER_DAY = 375;

    public enum State { PAUSED, PLAYING, DONE, FAILED, CANCELLED;

        public boolean finished() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }
    }

    /** Replay speed: simulated minutes per wall minute; MAX runs without pause. */
    public enum Speed {
        X1(1), X10(10), X60(60), X300(300), MAX(0);

        private final int factor;

        Speed(int factor) {
            this.factor = factor;
        }

        /** Wall milliseconds per replayed minute (0 at MAX). */
        public long wallMillisPerStep() {
            return factor == 0 ? 0 : 60_000L / factor;
        }

        public String label() {
            return factor == 0 ? "MAX" : Integer.toString(factor);
        }

        public static Speed of(String text) {
            for (Speed s : values()) {
                if (s.label().equalsIgnoreCase(text.trim()) || s.name().equalsIgnoreCase(text.trim())) {
                    return s;
                }
            }
            throw new IllegalArgumentException("speed must be one of 1, 10, 60, 300, MAX");
        }
    }

    /** What a finished session produced. */
    public record Result(int fills, Money friction, Money netPnl, String hash) {}

    /** Progress as {@code step/375} of the current day. */
    public String progress() {
        return step + "/" + STEPS_PER_DAY;
    }
}
