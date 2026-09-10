package money.hejje.llm;

import java.util.concurrent.Flow;

/** PRD 66C conceptual interface. Implementations must never log or return credentials. */
public interface LlmProvider {

    /** Provider name as configured under {@code hejje.llm.providers}. */
    String name();

    /** Completes with the given model ({@code model} may be null for the provider default). */
    LlmResponse complete(LlmRequest request, String model);

    /**
     * Streams the completion: text deltas, then a last chunk carrying the full response. The default delivers the whole
     * completion as one chunk; the HTTP adapters stream natively.
     */
    default Flow.Publisher<LlmChunk> stream(LlmRequest request, String model) {
        return LlmStreams.cold(emit -> {
            LlmResponse response = complete(request, model);
            emit.accept(new LlmChunk(response.text(), true, response));
        });
    }
}
