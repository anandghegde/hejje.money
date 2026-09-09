package money.hejje.context;

import java.util.List;

/**
 * One row of the Context Card.
 *
 * @param value    display value ("Strong", "Favorable", "Bullish +0.4", "High", ...)
 * @param delta    score points this indicator contributes (null when it is not a score adjuster)
 * @param evidence the adjuster's / service's evidence lines
 */
public record ContextItem(String name, Status status, String value, Integer delta, List<String> evidence) {

    public enum Status { GREEN, AMBER, RED, UNKNOWN }

    public ContextItem {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static ContextItem unknown(String name, String reason) {
        return new ContextItem(name, Status.UNKNOWN, "Unknown", null, List.of(reason));
    }
}
