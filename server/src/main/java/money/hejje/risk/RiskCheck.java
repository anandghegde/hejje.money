package money.hejje.risk;

/**
 * One risk control's result.
 *
 * @param name     stable check name
 * @param passed   whether the control allowed the intent
 * @param observed the observed value (as text), or null
 * @param limit    the configured limit (as text), or null
 * @param message  human-readable explanation
 */
public record RiskCheck(String name, boolean passed, String observed, String limit, String message) {

    public static RiskCheck pass(String name, String message) {
        return new RiskCheck(name, true, null, null, message);
    }

    public static RiskCheck fail(String name, String observed, String limit, String message) {
        return new RiskCheck(name, false, observed, limit, message);
    }
}
