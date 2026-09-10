package money.hejje.llm.internal;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import money.hejje.llm.LlmException;

/** Shared HTTP plumbing for the provider adapters: error mapping (timeouts, I/O, 429 and 5xx are retryable) and SSE lines. */
final class LlmHttp {

    private LlmHttp() {
    }

    static HttpResponse<String> send(HttpClient http, HttpRequest request, String provider) {
        return send(http, request, HttpResponse.BodyHandlers.ofString(), provider);
    }

    static HttpResponse<Stream<String>> sendStreaming(HttpClient http, HttpRequest request, String provider) {
        return send(http, request, HttpResponse.BodyHandlers.ofLines(), provider);
    }

    private static <T> HttpResponse<T> send(HttpClient http, HttpRequest request, HttpResponse.BodyHandler<T> handler, String provider) {
        try {
            return http.send(request, handler);
        } catch (HttpTimeoutException e) {
            throw new LlmException("Timeout calling " + provider, true, e);
        } catch (IOException e) {
            throw new LlmException("I/O error calling " + provider + ": " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted calling " + provider, false, e);
        }
    }

    /** Throws for anything but 200: 429 and 5xx retryable, other statuses not (with a truncated body for diagnosis). */
    static void check(int status, Supplier<String> body, String provider) {
        if (status == 200) {
            return;
        }
        if (status == 429 || status >= 500) {
            throw new LlmException("HTTP " + status + " from " + provider, true);
        }
        throw new LlmException("HTTP " + status + " from " + provider + ": " + truncate(body.get()), false);
    }

    /** The payload of an SSE {@code data:} line, or null for any other line. */
    static String sseData(String line) {
        return line != null && line.startsWith("data:") ? line.substring(5).trim() : null;
    }

    static String truncate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
