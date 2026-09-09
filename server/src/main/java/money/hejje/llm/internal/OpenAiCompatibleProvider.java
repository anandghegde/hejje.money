package money.hejje.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import money.hejje.llm.LlmException;
import money.hejje.llm.LlmProvider;
import money.hejje.llm.LlmProperties;
import money.hejje.llm.LlmRequest;
import money.hejje.llm.LlmResponse;

/**
 * Chat-completions client for any OpenAI-style endpoint ({@code POST {base-url}/chat/completions}). The API key comes
 * from the environment variable named in the config and is only ever put in the Authorization header.
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private final String name;
    private final LlmProperties.Provider config;
    private final HttpClient http;
    private final ObjectMapper json;

    public OpenAiCompatibleProvider(String name, LlmProperties.Provider config, ObjectMapper json) {
        this.name = name;
        this.config = config;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public LlmResponse complete(LlmRequest request, String model) {
        String effectiveModel = model != null ? model : config.model();
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new LlmException.Unavailable("Provider '" + name + "' has no base-url");
        }
        ObjectNode body = json.createObjectNode();
        body.put("model", effectiveModel);
        ArrayNode messages = body.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        messages.addObject().put("role", "user").put("content", request.userPrompt());
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.jsonMode()) {
            body.putObject("response_format").put("type", "json_object");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions")).timeout(config.timeout())
                .header("Content-Type", "application/json");
        String key = config.apiKeyEnv() == null ? null : System.getenv(config.apiKeyEnv());
        if (key != null && !key.isBlank()) {
            b.header("Authorization", "Bearer " + key);
        }
        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new LlmException("Timeout calling " + name, true, e);
        } catch (IOException e) {
            throw new LlmException("I/O error calling " + name + ": " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted calling " + name, false, e);
        }
        long latency = (System.nanoTime() - started) / 1_000_000;
        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw new LlmException("HTTP " + status + " from " + name, true);
        }
        if (status != 200) {
            throw new LlmException("HTTP " + status + " from " + name + ": " + truncate(response.body()), false);
        }
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            String text = choice.path("message").path("content").asText(null);
            if (text == null) {
                throw new LlmException("No content in response from " + name, false);
            }
            JsonNode usage = root.path("usage");
            return new LlmResponse(text, name, root.path("model").asText(effectiveModel), usage.hasNonNull("prompt_tokens") ? usage.path("prompt_tokens").asInt() : null,
                    usage.hasNonNull("completion_tokens") ? usage.path("completion_tokens").asInt() : null, latency);
        } catch (IOException e) {
            throw new LlmException("Unparseable response from " + name, false, e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
