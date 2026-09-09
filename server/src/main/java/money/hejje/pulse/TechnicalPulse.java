package money.hejje.pulse;

import java.util.List;

/**
 * PRD 16.1 output: {@code BULLISH — STRONG} with a −100..+100 score and the components behind it.
 *
 * @param score    Σ weight × value over the available components, normalised by their weights, × 100
 * @param coverage share of the total rule weight that had inputs (0..1)
 */
public record TechnicalPulse(PulseDirection direction, PulseStrength strength, int score, double coverage, List<PulseComponent> components,
        List<String> evidence) {

    public TechnicalPulse {
        components = components == null ? List.of() : List.copyOf(components);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public String label() {
        return direction + " — " + strength;
    }
}
