package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named, versioned set of Jev questions ({@code config/jev/<name>.yaml}, docs/jev.md). Changing a question, an option
 * or a threshold that reads its answers bumps {@code version}; answers are never compared across versions.
 * {@code params} holds the file's {@code params} block (thresholds, weights, templates the caller applies in code), an
 * empty object when there is none.
 */
public record JevQuestionSet(String name, String version, Map<String, JevQuestion> questions, JsonNode params) {

    public JevQuestionSet(String name, String version, Map<String, JevQuestion> questions) {
        this(name, version, questions, null);
    }

    public JevQuestionSet {
        if (name == null || !name.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("A question set name is lower-case letters, digits, '-' or '_'");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Question set " + name + " needs a version");
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("Question set " + name + " has no questions");
        }
        questions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(questions));
        params = params == null || params.isNull() || params.isMissingNode() ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : params;
    }

    /** Reads {@code name}, {@code version} and {@code questions} (each in the API's shape). */
    public static JevQuestionSet fromJson(JsonNode root) {
        Map<String, JevQuestion> questions = new LinkedHashMap<>();
        root.path("questions").fields().forEachRemaining(e -> {
            try {
                questions.put(e.getKey(), JevQuestion.fromJson(e.getValue()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Question '" + e.getKey() + "': " + ex.getMessage(), ex);
            }
        });
        return new JevQuestionSet(root.path("name").asText(null), root.path("version").asText(null), questions, root.get("params"));
    }

    /** A copy with {@code extra} questions added (per-call questions, e.g. one per symbol). Same name and version. */
    public JevQuestionSet with(Map<String, JevQuestion> extra) {
        Map<String, JevQuestion> all = new LinkedHashMap<>(questions);
        all.putAll(extra);
        return new JevQuestionSet(name, version, all, params);
    }

    /** A copy without {@code keys} (e.g. the order-book questions when the state has no order-book data). Same name and version. */
    public JevQuestionSet without(java.util.Collection<String> keys) {
        Map<String, JevQuestion> all = new LinkedHashMap<>(questions);
        keys.forEach(all::remove);
        return new JevQuestionSet(name, version, all, params);
    }
}
