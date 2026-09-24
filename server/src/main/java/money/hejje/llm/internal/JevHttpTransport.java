package money.hejje.llm.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import money.hejje.llm.JevProperties;
import money.hejje.llm.JevTransport;
import money.hejje.llm.LlmException;

/**
 * {@code POST {base-url}/v1/decisions} with {@code Authorization: Bearer <key>} (Surplus Intelligence API, docs/jev.md).
 * The key is read from the environment on every call and only ever put in the header.
 */
public class JevHttpTransport implements JevTransport {

    private static final String NAME = "jev";

    private final JevProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    public JevHttpTransport(JevProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
    }

    @Override
    public JsonNode send(ObjectNode request, Duration timeout) {
        String key = props.apiKeyEnv() == null ? null : System.getenv(props.apiKeyEnv());
        if (key == null || key.isBlank()) {
            throw new LlmException("No Jev API key in " + props.apiKeyEnv(), false);
        }
        String body;
        try {
            body = json.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new LlmException("Unserialisable Jev request: " + e.getMessage(), false, e);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(stripSlash(props.baseUrl()) + "/v1/decisions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = LlmHttp.send(http, httpRequest, NAME);
        LlmHttp.check(response.statusCode(), response::body, NAME);
        try {
            JsonNode root = json.readTree(response.body());
            if (!root.path("answers").isObject()) {
                throw new LlmException("No answers in the Jev response", false);
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new LlmException("Unparseable Jev response: " + LlmHttp.truncate(response.body()), false, e);
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
