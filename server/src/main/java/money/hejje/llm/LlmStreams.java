package money.hejje.llm;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Consumer;

/** Helpers for {@link LlmProvider#stream}. */
public final class LlmStreams {

    /** Produces chunks by calling {@code emit}; throwing ends the stream with that error. */
    @FunctionalInterface
    public interface Producer {
        void produce(Consumer<LlmChunk> emit);
    }

    private LlmStreams() {
    }

    /**
     * A cold publisher: the producer runs on a virtual thread only once a subscriber has arrived, so no chunk is lost to
     * a late subscription (a {@link SubmissionPublisher} drops items submitted before anyone subscribes).
     */
    public static Flow.Publisher<LlmChunk> cold(Producer producer) {
        return subscriber -> {
            SubmissionPublisher<LlmChunk> publisher = new SubmissionPublisher<>();
            publisher.subscribe(subscriber);
            Thread.ofVirtual().start(() -> {
                try {
                    producer.produce(publisher::submit);
                    publisher.close();
                } catch (RuntimeException e) {
                    publisher.closeExceptionally(e);
                }
            });
        };
    }
}
