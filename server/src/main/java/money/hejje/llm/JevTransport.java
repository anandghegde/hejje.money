package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;

/**
 * Sends one request body ({@code model, state, questions}) to Jev and returns the response body. Throws
 * {@link LlmException} with {@code retryable} set for timeouts, I/O errors, 429, 529 and other 5xx.
 */
public interface JevTransport {

    JsonNode send(ObjectNode request, Duration timeout);
}
