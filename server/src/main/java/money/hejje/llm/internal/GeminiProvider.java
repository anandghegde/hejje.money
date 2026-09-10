package money.hejje.llm.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import money.hejje.llm.LlmChunk;
import money.hejje.llm.LlmException;
import money.hejje.llm.LlmMessage;
import money.hejje.llm.LlmProperties;
import money.hejje.llm.LlmProvider;
import money.hejje.llm.LlmRequest;
import money.hejje.llm.LlmResponse;
import money.hejje.llm.LlmStreams;
import money.hejje.llm.LlmTool;
import money.hejje.llm.LlmToolCall;

/**
 * Native Gemini API adapter ({@code POST {base-url}/models/{model}:generateContent}, streaming via
 * {@code :streamGenerateContent?alt=sse}): system instruction, user/model contents, function declarations, function
 * calls and responses, JSON mode ({@code responseMimeType}). The key goes in the {@code x-goog-api-key} header only.
 * Function-declaration schemas are reduced to the OpenAPI subset Gemini accepts.
 */
public class GeminiProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private static final Set<String> SCHEMA_KEYS = Set.of("type", "format", "description", "nullable", "enum", "properties", "required", "items",
            "minimum", "maximum", "minItems", "maxItems", "anyOf");

    private final String name;
    private final LlmProperties.Provider config;
    private final HttpClient http;
    private final ObjectMapper json;

    public GeminiProvider(String name, LlmProperties.Provider config, ObjectMapper json) {
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
        String m = model(model);
        long started = System.nanoTime();
        HttpResponse<String> response = LlmHttp.send(http, httpRequest(request, m, false), name);
        long latency = (System.nanoTime() - started) / 1_000_000;
        LlmHttp.check(response.statusCode(), response::body, name);
        try {
            Accumulator acc = new Accumulator();
            acc.accept(json.readTree(response.body()), null);
            return acc.response(m, latency);
        } catch (IOException e) {
            throw new LlmException("Unparseable response from " + name, false, e);
        }
    }

    @Override
    public Flow.Publisher<LlmChunk> stream(LlmRequest request, String model) {
        String m = model(model);
        return LlmStreams.cold(emit -> {
            long started = System.nanoTime();
            HttpResponse<Stream<String>> response = LlmHttp.sendStreaming(http, httpRequest(request, m, true), name);
            try (Stream<String> lines = response.body()) {
                LlmHttp.check(response.statusCode(), () -> lines.collect(Collectors.joining("\n")), name);
                Accumulator acc = new Accumulator();
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String data = LlmHttp.sseData(it.next());
                    if (data != null && !data.isEmpty()) {
                        acc.accept(json.readTree(data), emit);
                    }
                }
                emit.accept(new LlmChunk("", true, acc.response(m, (System.nanoTime() - started) / 1_000_000)));
            } catch (JsonProcessingException e) {
                throw new LlmException("Unparseable stream event from " + name, false, e);
            } catch (UncheckedIOException e) {
                throw new LlmException("Stream from " + name + " broke: " + e.getMessage(), true, e);
            }
        });
    }

    private String model(String model) {
        String m = model != null ? model : config.model();
        if (m == null || m.isBlank()) {
            throw new LlmException.Unavailable("Provider '" + name + "' has no model");
        }
        return m.startsWith("models/") ? m.substring(7) : m;
    }

    private HttpRequest httpRequest(LlmRequest request, String model, boolean stream) {
        String base = config.baseUrl() == null || config.baseUrl().isBlank() ? DEFAULT_BASE_URL : config.baseUrl().replaceAll("/+$", "");
        String url = base + "/models/" + model + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(config.timeout()).header("Content-Type", "application/json");
        String key = config.apiKeyEnv() == null ? null : System.getenv(config.apiKeyEnv());
        if (key != null && !key.isBlank()) {
            b.header("x-goog-api-key", key);
        }
        try {
            return b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(request)))).build();
        } catch (JsonProcessingException e) {
            throw new LlmException("Request for " + name + " not serialisable", false, e);
        }
    }

    ObjectNode body(LlmRequest request) {
        ObjectNode body = json.createObjectNode();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.systemPrompt());
        }
        ArrayNode contents = body.putArray("contents");
        ArrayNode functionResponses = null; // consecutive tool turns share one user content
        for (LlmMessage m : request.conversation()) {
            if (m.role() == LlmMessage.Role.TOOL) {
                if (functionResponses == null) {
                    ObjectNode content = contents.addObject().put("role", "user");
                    functionResponses = content.putArray("parts");
                }
                ObjectNode fr = functionResponses.addObject().putObject("functionResponse");
                fr.put("name", m.toolName());
                fr.set("response", responseObject(m.content()));
                continue;
            }
            functionResponses = null;
            ObjectNode content = contents.addObject().put("role", m.role() == LlmMessage.Role.USER ? "user" : "model");
            ArrayNode parts = content.putArray("parts");
            if (m.content() != null && !m.content().isEmpty()) {
                parts.addObject().put("text", m.content());
            }
            for (LlmToolCall c : m.toolCalls()) {
                ObjectNode fc = parts.addObject().putObject("functionCall");
                fc.put("name", c.name());
                fc.set("args", c.arguments() == null ? json.createObjectNode() : c.arguments());
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (LlmTool t : request.tools()) {
                ObjectNode d = declarations.addObject().put("name", t.name()).put("description", t.description());
                if (t.parameters() != null && t.parameters().path("properties").size() > 0) {
                    d.set("parameters", sanitize(t.parameters()));
                }
            }
        }
        ObjectNode generation = json.createObjectNode();
        if (request.temperature() != null) {
            generation.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            generation.put("maxOutputTokens", request.maxTokens());
        }
        if (request.jsonMode()) {
            generation.put("responseMimeType", "application/json");
        }
        if (!generation.isEmpty()) {
            body.set("generationConfig", generation);
        }
        return body;
    }

    /** Keeps the schema keys Gemini's function declarations accept; a type list becomes its first non-null type plus nullable. */
    JsonNode sanitize(JsonNode schema) {
        ObjectNode out = json.createObjectNode();
        schema.fieldNames().forEachRemaining(key -> {
            if (!SCHEMA_KEYS.contains(key)) {
                return;
            }
            JsonNode v = schema.get(key);
            switch (key) {
                case "properties" -> {
                    ObjectNode props = out.putObject("properties");
                    v.fieldNames().forEachRemaining(p -> props.set(p, sanitize(v.get(p))));
                }
                case "items" -> out.set("items", sanitize(v));
                case "anyOf" -> {
                    ArrayNode any = out.putArray("anyOf");
                    v.forEach(s -> any.add(sanitize(s)));
                }
                case "type" -> {
                    if (v.isArray()) {
                        for (JsonNode t : v) {
                            if ("null".equals(t.asText())) {
                                out.put("nullable", true);
                            } else if (!out.has("type")) {
                                out.put("type", t.asText());
                            }
                        }
                    } else {
                        out.set("type", v);
                    }
                }
                default -> out.set(key, v);
            }
        });
        return out;
    }

    private JsonNode responseObject(String content) {
        try {
            JsonNode parsed = content == null ? null : json.readTree(content);
            if (parsed != null && parsed.isObject()) {
                return parsed;
            }
        } catch (IOException ignored) {
            // not JSON: wrap below
        }
        return json.createObjectNode().put("result", content == null ? "" : content);
    }

    /** Folds one or more GenerateContentResponse objects (a stream sends several) into one response. */
    private final class Accumulator {
        final StringBuilder text = new StringBuilder();
        final List<LlmToolCall> calls = new ArrayList<>();
        Integer inputTokens;
        Integer outputTokens;
        String finishReason;
        String modelVersion;
        String blockReason;

        void accept(JsonNode root, Consumer<LlmChunk> emit) {
            if (root.path("modelVersion").isTextual()) {
                modelVersion = root.path("modelVersion").asText();
            }
            JsonNode candidate = root.path("candidates").path(0);
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.path("text").isTextual() && !part.path("thought").asBoolean(false)) {
                    String piece = part.path("text").asText();
                    text.append(piece);
                    if (emit != null && !piece.isEmpty()) {
                        emit.accept(new LlmChunk(piece, false));
                    }
                }
                if (part.has("functionCall")) {
                    JsonNode fc = part.get("functionCall");
                    calls.add(new LlmToolCall(fc.path("id").asText("gemini_call_" + calls.size()), fc.path("name").asText(),
                            fc.path("args").isObject() ? fc.get("args") : json.createObjectNode()));
                }
            }
            if (candidate.path("finishReason").isTextual()) {
                finishReason = candidate.path("finishReason").asText();
            }
            JsonNode usage = root.path("usageMetadata");
            if (usage.hasNonNull("promptTokenCount")) {
                inputTokens = usage.path("promptTokenCount").asInt();
            }
            if (usage.hasNonNull("candidatesTokenCount")) {
                outputTokens = usage.path("candidatesTokenCount").asInt();
            }
            if (root.path("promptFeedback").path("blockReason").isTextual()) {
                blockReason = root.path("promptFeedback").path("blockReason").asText();
            }
        }

        LlmResponse response(String model, long latency) {
            if (text.isEmpty() && calls.isEmpty()) {
                throw new LlmException(blockReason != null ? "Prompt blocked by " + name + ": " + blockReason
                        : "No content in response from " + name + (finishReason != null ? " (" + finishReason + ")" : ""), false);
            }
            return new LlmResponse(text.toString(), name, modelVersion != null ? modelVersion : model, inputTokens, outputTokens, latency, calls, finishReason);
        }
    }
}
