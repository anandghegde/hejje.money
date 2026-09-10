package money.hejje.llm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic provider for tests and offline development ({@code type: fixture}): answers come from a scripted
 * responder (which may request tool calls), from responses registered by prompt hash or by a substring of the user
 * prompt, or from a fallback function; unregistered prompts fail as non-retryable. Records every request so tests can
 * assert prompts. Streams word by word.
 */
public class FixtureLlmProvider implements LlmProvider {

    private static final Pattern WORD = Pattern.compile("\\S+\\s*|\\s+");

    private final String name;
    private final Map<String, String> byHash = new ConcurrentHashMap<>();
    private final Map<String, String> byContains = new ConcurrentHashMap<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Function<LlmRequest, String> fallback;
    private volatile Function<LlmRequest, LlmResponse> responder;

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

    /**
     * Scripts full responses (text and/or tool calls); consulted first, a null result falls through to the registered
     * answers. Build responses with {@link #text(String)} and {@link #calls(LlmToolCall...)}.
     */
    public void responder(Function<LlmRequest, LlmResponse> responder) {
        this.responder = responder;
    }

    public static LlmResponse text(String text) {
        return new LlmResponse(text, null, null, null, null, 0, List.of(), "stop");
    }

    public static LlmResponse calls(LlmToolCall... calls) {
        return new LlmResponse("", null, null, null, null, 0, List.of(calls), "tool_calls");
    }

    public void reset() {
        byHash.clear();
        byContains.clear();
        requests.clear();
        fallback = null;
        responder = null;
    }

    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public LlmResponse complete(LlmRequest request, String model) {
        requests.add(request);
        String effectiveModel = model == null ? "fixture" : model;
        int in = (request.promptText().length() + (request.systemPrompt() == null ? 0 : request.systemPrompt().length())) / 4;
        Function<LlmRequest, LlmResponse> r = responder;
        LlmResponse scripted = r == null ? null : r.apply(request);
        if (scripted != null) {
            return new LlmResponse(scripted.text(), name, effectiveModel, in, Math.max(1, scripted.text().length() / 4), 1, scripted.toolCalls(),
                    scripted.finishReason());
        }
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
        return new LlmResponse(text, name, effectiveModel, in, text.length() / 4, 1);
    }

    @Override
    public Flow.Publisher<LlmChunk> stream(LlmRequest request, String model) {
        return LlmStreams.cold(emit -> {
            LlmResponse response = complete(request, model);
            Matcher m = WORD.matcher(response.text());
            while (m.find()) {
                emit.accept(new LlmChunk(m.group(), false));
            }
            emit.accept(new LlmChunk("", true, response));
        });
    }
}
