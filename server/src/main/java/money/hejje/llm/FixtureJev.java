package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Deterministic Jev for tests and offline development ({@code hejje.jev.base-url: fixture}). Answers come from a
 * scripted function of (question key, state) when one is set for the key, else a neutral default: noul 0.5, the first
 * option at probability 1, score 0. Every answer is shaped like the API's.
 */
public class FixtureJev implements JevTransport {

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, BiFunction<JsonNode, JsonNode, JsonNode>> scripts = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile RuntimeException failure;

    /** Answers {@code key} with {@code answer(question, state)}, an answer object in the API's shape. */
    public void script(String key, BiFunction<JsonNode, JsonNode, JsonNode> answer) {
        scripts.put(key, answer);
    }

    public void noul(String key, double value) {
        script(key, (q, s) -> json.createObjectNode().put("type", "noul").put("noul", value));
    }

    /** Every following call fails with {@code e} until {@link #reset()}. */
    public void failWith(RuntimeException e) {
        this.failure = e;
    }

    public void reset() {
        scripts.clear();
        failure = null;
        calls.set(0);
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public JsonNode send(ObjectNode request, Duration timeout) {
        calls.incrementAndGet();
        RuntimeException f = failure;
        if (f != null) {
            throw f;
        }
        JsonNode state = request.path("state");
        ObjectNode answers = json.createObjectNode();
        request.path("questions").fields().forEachRemaining(e -> {
            BiFunction<JsonNode, JsonNode, JsonNode> s = scripts.get(e.getKey());
            answers.set(e.getKey(), s != null ? s.apply(e.getValue(), state) : neutral(e.getValue()));
        });
        ObjectNode root = json.createObjectNode();
        root.put("model", "fixture-jev");
        root.set("answers", answers);
        root.putObject("usage").put("input_tokens", Math.max(1, request.toString().length() / 4)).put("output_tokens", 0);
        return root;
    }

    private ObjectNode neutral(JsonNode question) {
        ObjectNode a = json.createObjectNode();
        String type = question.path("type").asText();
        a.put("type", type);
        switch (type) {
            case "choice" -> {
                String first = question.path("criteria").fieldNames().next();
                a.put("choice", first);
                ObjectNode p = a.putObject("probabilities");
                question.path("criteria").fieldNames().forEachRemaining(k -> p.put(k, k.equals(first) ? 1.0 : 0.0));
                a.put("confidence", 1.0);
            }
            case "score" -> {
                a.put("score", 0.0);
                ObjectNode p = a.putObject("probabilities");
                for (int i = 0; i < question.path("criteria").size(); i++) {
                    p.put(String.valueOf(i), i == 0 ? 1.0 : 0.0);
                }
                a.put("confidence", 1.0);
            }
            default -> a.put("noul", 0.5);
        }
        return a;
    }
}
