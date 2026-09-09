package money.hejje.strategy.dsl;

/**
 * Result of one condition at one bar. The observed sides feed evidence lists (README rule 11).
 *
 * @param condition   canonical text of the condition
 * @param status      PASSED, FAILED or NOT_READY
 * @param observedLhs value of the left side, or null when not ready
 * @param observedRhs value of the right side, or null when not ready
 */
public record EvalResult(String condition, EvalStatus status, Double observedLhs, Double observedRhs) {

    public boolean passed() {
        return status == EvalStatus.PASSED;
    }

    public static EvalResult notReady(Condition condition) {
        return new EvalResult(condition.text(), EvalStatus.NOT_READY, null, null);
    }
}
