package money.hejje.llm;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/** PRD 66C conceptual interface. Implementations must never log or return credentials. */
public interface LlmProvider {

    /** Provider name as configured under {@code hejje.llm.providers}. */
    String name();

    /** Completes with the given model ({@code model} may be null for the provider default). */
    LlmResponse complete(LlmRequest request, String model);

    /** Streams the completion; the default delivers the whole completion as one chunk (native streaming arrives with M4.1). */
    default Flow.Publisher<LlmChunk> stream(LlmRequest request, String model) {
        SubmissionPublisher<LlmChunk> publisher = new SubmissionPublisher<>();
        Thread.ofVirtual().start(() -> {
            try {
                LlmResponse response = complete(request, model);
                publisher.submit(new LlmChunk(response.text(), true));
                publisher.close();
            } catch (RuntimeException e) {
                publisher.closeExceptionally(e);
            }
        });
        return publisher;
    }
}
