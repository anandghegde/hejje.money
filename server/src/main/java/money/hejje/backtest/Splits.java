package money.hejje.backtest;

/**
 * How sessions are assigned to splits (PRD section 12.4).
 * <ul>
 * <li>{@code NONE}: everything is in-sample.</li>
 * <li>{@code FIXED}: the first {@code inSamplePct} of sessions are in-sample, the next {@code validationPct}
 * validation, the rest out-of-sample.</li>
 * <li>{@code WALK_FORWARD}: rolling windows of {@code trainMonths} then {@code testMonths}; sessions inside any
 * test window are out-of-sample, the others in-sample. {@code anchored} keeps the first train start fixed.</li>
 * </ul>
 */
public record Splits(Type type, Integer inSamplePct, Integer validationPct, Integer outOfSamplePct, Integer trainMonths,
        Integer testMonths, Boolean anchored) {

    public enum Type { NONE, FIXED, WALK_FORWARD }

    public static final Splits NONE = new Splits(Type.NONE, null, null, null, null, null, null);
    public static final Splits DEFAULT_FIXED = new Splits(Type.FIXED, 60, 20, 20, null, null, null);

    public static Splits fixed(int inSample, int validation, int outOfSample) {
        return new Splits(Type.FIXED, inSample, validation, outOfSample, null, null, null);
    }

    public static Splits walkForward(int trainMonths, int testMonths, boolean anchored) {
        return new Splits(Type.WALK_FORWARD, null, null, null, trainMonths, testMonths, anchored);
    }

    public Splits {
        if (type == null) {
            type = Type.NONE;
        }
        if (type == Type.FIXED) {
            int is = inSamplePct == null ? 60 : inSamplePct;
            int val = validationPct == null ? 20 : validationPct;
            int oos = outOfSamplePct == null ? Math.max(0, 100 - is - val) : outOfSamplePct;
            if (is < 0 || val < 0 || oos < 0 || is + val + oos != 100) {
                throw new IllegalArgumentException("Fixed split percentages must be non-negative and sum to 100");
            }
            inSamplePct = is;
            validationPct = val;
            outOfSamplePct = oos;
        }
        if (type == Type.WALK_FORWARD) {
            if (trainMonths == null || trainMonths < 1 || testMonths == null || testMonths < 1) {
                throw new IllegalArgumentException("Walk-forward needs trainMonths and testMonths of at least 1");
            }
            if (anchored == null) {
                anchored = false;
            }
        }
    }

    /** True when the split scheme yields an out-of-sample slice (needed for VALIDATED). */
    public boolean hasOutOfSample() {
        return type == Type.WALK_FORWARD || (type == Type.FIXED && outOfSamplePct > 0);
    }
}
