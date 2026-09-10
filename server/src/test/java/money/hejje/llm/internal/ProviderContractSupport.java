package money.hejje.llm.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import money.hejje.llm.LlmChunk;

/** Helpers for the provider contract tests: HTTP fixtures from {@code test/resources/llm/} and draining a stream. */
final class ProviderContractSupport {

    /** The key env var for the contract tests: any variable that is always set proves the key is read from the environment. */
    static final String KEY_ENV = "HOME";

    private ProviderContractSupport() {
    }

    static String fixture(String path) {
        try (InputStream in = ProviderContractSupport.class.getClassLoader().getResourceAsStream("llm/" + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Subscribes and waits for completion; rethrows the stream's error. */
    static List<LlmChunk> drain(Flow.Publisher<LlmChunk> publisher) throws Throwable {
        List<LlmChunk> out = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(LlmChunk item) {
                out.add(item);
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        }
        return out;
    }
}
