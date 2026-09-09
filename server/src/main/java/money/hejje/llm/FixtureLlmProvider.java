package money.hejje.llm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Deterministic provider for tests and offline development ({@code type: fixture}): answers come from responses
 * registered by prompt hash, by a substring of the user prompt, or from a fallback function; unregistered prompts fail
 * as non-retryable. Records every request so tests can assert prompts.
 */
public class FixtureLlmProvider implements LlmProvider {

    private final String name;
    private final Map<String, String> byHash = new ConcurrentHashMap<>();
    private final Map<String, String> byContains = new ConcurrentHashMap<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Function<LlmRequest, String> fallback;

    public FixtureLlmProvider(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    /** Registers the answer for the exact prompt (system + user), keyed by its hash (see {@link Prompts#hash}). */
    public void respondTo(String systemPrompt, String userPrompt, String response) {
        byHash.put(Prompts.hash(systemPrompt, userPrompt), response);
    }

    /** Registers the answer for any user prompt containing {@code needle}. */
    public void respondWhenContains(String needle, String response) {
        byContains.put(needle, response);
    }

    public void fallback(Function<LlmRequest, String> fallback) {
        this.fallback = fallback;
    }

    public void reset() {
        byHash.clear();
        byContains.clear();
        requests.clear();
        fallback = null;
    }

    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public LlmResponse complete(LlmRequest request, String model) {
        requests.add(request);
        String hash = Prompts.hash(request.systemPrompt(), request.userPrompt());
        String text = byHash.get(hash);
        if (text == null) {
            for (Map.Entry<String, String> e : byContains.entrySet()) {
                if (request.userPrompt().contains(e.getKey())) {
                    text = e.getValue();
                    break;
                }
            }
        }
        if (text == null && fallback != null) {
            text = fallback.apply(request);
        }
        if (text == null) {
            throw new LlmException("No fixture response for prompt " + hash + " (" + request.purpose() + ")", false);
        }
        int in = (request.userPrompt().length() + (request.systemPrompt() == null ? 0 : request.systemPrompt().length())) / 4;
        return new LlmResponse(text, name, model == null ? "fixture" : model, in, text.length() / 4, 1);
    }
}
