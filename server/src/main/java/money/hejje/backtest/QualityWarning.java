package money.hejje.backtest;

import java.util.Map;

/** A PRD section 12.3 quality finding. {@code FAIL} blocks VALIDATED; {@code WARN} is informational. */
public record QualityWarning(String code, Severity severity, String message, Map<String, Object> evidence) {

    public enum Severity { WARN, FAIL }

    public QualityWarning {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
