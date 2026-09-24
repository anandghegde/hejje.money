package money.hejje.ratings;

/** Chart patterns the detectors recognise on D1 (docs/ratings.md, "Bases"). */
public enum BaseType {
    CUP_WITH_HANDLE, CUP, DOUBLE_BOTTOM, FLAT_BASE, MA_REVERSAL;

    /** A moving-average reversal is a one-session setup with a nearer stop and goal; the others are bases. */
    public boolean reversal() {
        return this == MA_REVERSAL;
    }
}
