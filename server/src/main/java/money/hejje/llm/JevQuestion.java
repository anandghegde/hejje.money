package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One typed Jev question (TypeSafe API, docs/jev.md). {@code instructions} and every criterion may be a string, an
 * object or an array, as the API allows; the factories take plain strings.
 */
public sealed interface JevQuestion {

    JsonNode instructions();

    /** The question as the API expects it inside {@code questions}. */
    ObjectNode toJson();

    /** Yes/no: the answer is the probability of yes. */
    record Noul(JsonNode instructions, JsonNode whenTrue, JsonNode whenFalse) implements JevQuestion {

        public Noul {
            requireInstructions(instructions);
        }

        @Override
        public ObjectNode toJson() {
            ObjectNode q = base("noul", instructions);
            if (whenTrue != null || whenFalse != null) {
                ObjectNode c = q.putObject("criteria");
                if (whenTrue != null) {
                    c.set("true", whenTrue);
                }
                if (whenFalse != null) {
                    c.set("false", whenFalse);
                }
            }
            return q;
        }
    }

    /** One option out of 2..255; a null description means the option needs none. */
    record Choice(JsonNode instructions, Map<String, JsonNode> options) implements JevQuestion {

        public Choice {
            requireInstructions(instructions);
            if (options == null || options.size() < 2 || options.size() > 255) {
                throw new IllegalArgumentException("A choice needs 2 to 255 options");
            }
            options = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }

        @Override
        public ObjectNode toJson() {
            ObjectNode q = base("choice", instructions);
            ObjectNode c = q.putObject("criteria");
            options.forEach((k, v) -> c.set(k, v == null ? JsonNodeFactory.instance.nullNode() : v));
            return q;
        }
    }

    /** Ordered levels (2..10); the answer is the probability-weighted level index. */
    record Score(JsonNode instructions, List<JsonNode> levels) implements JevQuestion {

        public Score {
            requireInstructions(instructions);
            if (levels == null || levels.size() < 2 || levels.size() > 10) {
                throw new IllegalArgumentException("A score needs 2 to 10 levels");
            }
            levels = List.copyOf(levels);
        }

        @Override
        public ObjectNode toJson() {
            ObjectNode q = base("score", instructions);
            levels.forEach(q.putArray("criteria")::add);
            return q;
        }
    }

    static Noul noul(String instructions) {
        return new Noul(TextNode.valueOf(instructions), null, null);
    }

    static Noul noul(String instructions, String whenTrue, String whenFalse) {
        return new Noul(TextNode.valueOf(instructions), text(whenTrue), text(whenFalse));
    }

    /** Options in order; a null value means no description. */
    static Choice choice(String instructions, Map<String, String> options) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        options.forEach((k, v) -> m.put(k, text(v)));
        return new Choice(TextNode.valueOf(instructions), m);
    }

    static Score score(String instructions, List<String> levels) {
        List<JsonNode> l = new ArrayList<>();
        levels.forEach(s -> l.add(TextNode.valueOf(s)));
        return new Score(TextNode.valueOf(instructions), l);
    }

    /** Reads a question in the API's own shape ({@code type}, {@code instructions}, {@code criteria}). */
    static JevQuestion fromJson(JsonNode q) {
        JsonNode instructions = q.get("instructions");
        JsonNode criteria = q.path("criteria");
        return switch (q.path("type").asText()) {
            case "noul" -> new Noul(instructions, criteria.get("true"), criteria.get("false"));
            case "choice" -> {
                Map<String, JsonNode> options = new LinkedHashMap<>();
                criteria.fields().forEachRemaining(e -> options.put(e.getKey(), e.getValue().isNull() ? null : e.getValue()));
                yield new Choice(instructions, options);
            }
            case "score" -> {
                List<JsonNode> levels = new ArrayList<>();
                criteria.forEach(levels::add);
                yield new Score(instructions, levels);
            }
            default -> throw new IllegalArgumentException("Unknown question type '" + q.path("type").asText() + "' (noul, choice or score)");
        };
    }

    private static ObjectNode base(String type, JsonNode instructions) {
        ObjectNode q = new ObjectMapper().createObjectNode();
        q.put("type", type);
        q.set("instructions", instructions);
        return q;
    }

    private static JsonNode text(String s) {
        return s == null ? null : TextNode.valueOf(s);
    }

    private static void requireInstructions(JsonNode instructions) {
        if (instructions == null || instructions.isNull() || (instructions.isTextual() && instructions.asText().isBlank())) {
            throw new IllegalArgumentException("A question needs instructions");
        }
    }
}
