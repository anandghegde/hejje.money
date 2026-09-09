package money.hejje.scoring;

import java.util.List;

/**
 * One adjuster's contribution: a delta bounded to {@code [min, max]} with the evidence that produced it.
 *
 * @param evidence templated sentences built from observed values (README rule 11)
 */
public record Adjustment(String name, int delta, int min, int max, List<String> evidence) {

    public Adjustment {
        if (min > max) {
            throw new IllegalArgumentException("min > max");
        }
        delta = Math.max(min, Math.min(max, delta));
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static Adjustment none(String name, int min, int max, String reason) {
        return new Adjustment(name, 0, min, max, List.of(reason));
    }
}
