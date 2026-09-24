package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One typed answer. {@code noul} for yes/no questions; {@code choice} for choices; {@code score} (the
 * probability-weighted level index) for scores. {@code probabilities} maps each option, or each level index as a
 * string, to its probability. {@code confidence} comes with choices and scores; the API gives none for a noul.
 */
public record JevAnswer(String key, String type, String choice, Double score, Double noul, Map<String, Double> probabilities, Double confidence) {

    public JevAnswer {
        probabilities = probabilities == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
    }

    /** The probability of the answer given: the noul, or the chosen option's probability. Null for a score. */
    public Double probability() {
        if (noul != null) {
            return noul;
        }
        return choice == null ? null : probabilities.get(choice);
    }

    /** The probability of one option (or level index); 0 when the option is absent. */
    public double probabilityOf(String option) {
        return probabilities.getOrDefault(option, 0.0);
    }

    /**
     * Parses one answer of the API response. A noul may arrive as {@code noul} or as {@code probability} (older
     * shapes); a missing {@code type} is inferred from the fields present.
     */
    public static JevAnswer fromJson(String key, JsonNode a) {
        String type = a.path("type").asText("");
        if (type.isEmpty()) {
            type = a.has("choice") ? "choice" : a.has("score") ? "score" : "noul";
        }
        if (type.equals("boolean")) {
            type = "noul";
        }
        Double noul = type.equals("noul") ? number(a.has("noul") ? a.get("noul") : a.get("probability")) : null;
        Map<String, Double> probabilities = new LinkedHashMap<>();
        a.path("probabilities").fields().forEachRemaining(e -> {
            if (e.getValue().isNumber()) {
                probabilities.put(e.getKey(), e.getValue().asDouble());
            }
        });
        return new JevAnswer(key, type, a.hasNonNull("choice") ? a.get("choice").asText() : null, number(a.get("score")), noul, probabilities,
                number(a.get("confidence")));
    }

    private static Double number(JsonNode n) {
        return n == null || !n.isNumber() || !Double.isFinite(n.asDouble()) ? null : n.asDouble();
    }
}
