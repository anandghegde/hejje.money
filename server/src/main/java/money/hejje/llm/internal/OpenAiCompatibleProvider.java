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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Flow;
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
 * Chat-completions client for any OpenAI-style endpoint ({@code POST {base-url}/chat/completions}): messages, function
 * tools and tool calls, JSON mode, and SSE streaming ({@code stream: true}, usage from {@code stream_options}). The API
 * key comes from the environment variable named in the config and is only ever put in the Authorization header.
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
        HttpRequest httpRequest = httpRequest(request, effectiveModel, false);
        long started = System.nanoTime();
        HttpResponse<String> response = LlmHttp.send(http, httpRequest, name);
        long latency = (System.nanoTime() - started) / 1_000_000;
        LlmHttp.check(response.statusCode(), response::body, name);
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String text = message.path("content").isTextual() ? message.path("content").asText() : null;
            List<LlmToolCall> calls = new ArrayList<>();
            for (JsonNode tc : message.path("tool_calls")) {
                JsonNode fn = tc.path("function");
                calls.add(new LlmToolCall(tc.path("id").asText(null), fn.path("name").asText(), arguments(fn.path("arguments"))));
            }
            if (text == null && calls.isEmpty()) {
                throw new LlmException("No content in response from " + name, false);
            }
            JsonNode usage = root.path("usage");
            return new LlmResponse(text, name, root.path("model").asText(effectiveModel), intOrNull(usage, "prompt_tokens"),
                    intOrNull(usage, "completion_tokens"), latency, calls, choice.path("finish_reason").asText(null));
        } catch (IOException e) {
            throw new LlmException("Unparseable response from " + name, false, e);
        }
    }

    @Override
    public Flow.Publisher<LlmChunk> stream(LlmRequest request, String model) {
        String effectiveModel = model != null ? model : config.model();
        return LlmStreams.cold(emit -> {
            HttpRequest httpRequest = httpRequest(request, effectiveModel, true);
            long started = System.nanoTime();
            HttpResponse<Stream<String>> response = LlmHttp.sendStreaming(http, httpRequest, name);
            try (Stream<String> lines = response.body()) {
                LlmHttp.check(response.statusCode(), () -> lines.collect(Collectors.joining("\n")), name);
                StringBuilder text = new StringBuilder();
                Map<Integer, String[]> calls = new TreeMap<>(); // index -> {id, name, arguments}
                Integer in = null;
                Integer out = null;
                String finish = null;
                String seenModel = effectiveModel;
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String data = LlmHttp.sseData(it.next());
                    if (data == null || data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode event = json.readTree(data);
                    seenModel = event.path("model").asText(seenModel);
                    JsonNode choice = event.path("choices").path(0);
                    JsonNode delta = choice.path("delta");
                    if (delta.path("content").isTextual() && !delta.path("content").asText().isEmpty()) {
                        String piece = delta.path("content").asText();
                        text.append(piece);
                        emit.accept(new LlmChunk(piece, false));
                    }
                    for (JsonNode tc : delta.path("tool_calls")) {
                        String[] call = calls.computeIfAbsent(tc.path("index").asInt(0), i -> new String[] {null, null, ""});
                        if (tc.path("id").isTextual()) {
                            call[0] = tc.path("id").asText();
                        }
                        JsonNode fn = tc.path("function");
                        if (fn.path("name").isTextual()) {
                            call[1] = fn.path("name").asText();
                        }
                        if (fn.path("arguments").isTextual()) {
                            call[2] += fn.path("arguments").asText();
                        }
                    }
                    if (choice.path("finish_reason").isTextual()) {
                        finish = choice.path("finish_reason").asText();
                    }
                    JsonNode usage = event.path("usage");
                    if (usage.isObject()) {
                        in = intOrNull(usage, "prompt_tokens");
                        out = intOrNull(usage, "completion_tokens");
                    }
                }
                List<LlmToolCall> toolCalls = new ArrayList<>();
                for (String[] call : calls.values()) {
                    toolCalls.add(new LlmToolCall(call[0], call[1], arguments(json.getNodeFactory().textNode(call[2]))));
                }
                long latency = (System.nanoTime() - started) / 1_000_000;
                emit.accept(new LlmChunk("", true, new LlmResponse(text.toString(), name, seenModel, in, out, latency, toolCalls, finish)));
            } catch (JsonProcessingException e) {
                throw new LlmException("Unparseable stream event from " + name, false, e);
            } catch (UncheckedIOException e) {
                throw new LlmException("Stream from " + name + " broke: " + e.getMessage(), true, e);
            }
        });
    }

    private HttpRequest httpRequest(LlmRequest request, String model, boolean stream) {
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new LlmException.Unavailable("Provider '" + name + "' has no base-url");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions")).timeout(config.timeout())
                .header("Content-Type", "application/json");
        if (stream) {
            b.header("Accept", "text/event-stream");
        }
        String key = config.apiKeyEnv() == null ? null : System.getenv(config.apiKeyEnv());
        if (key != null && !key.isBlank()) {
            b.header("Authorization", "Bearer " + key);
        }
        try {
            return b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(request, model, stream)))).build();
        } catch (JsonProcessingException e) {
            throw new LlmException("Request for " + name + " not serialisable", false, e);
        }
    }

    ObjectNode body(LlmRequest request, String model, boolean stream) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        for (LlmMessage m : request.conversation()) {
            switch (m.role()) {
                case USER -> messages.addObject().put("role", "user").put("content", m.content());
                case ASSISTANT -> {
                    ObjectNode msg = messages.addObject().put("role", "assistant");
                    if (m.content() == null || m.content().isEmpty()) {
                        msg.putNull("content");
                    } else {
                        msg.put("content", m.content());
                    }
                    if (!m.toolCalls().isEmpty()) {
                        ArrayNode calls = msg.putArray("tool_calls");
                        for (LlmToolCall c : m.toolCalls()) {
                            ObjectNode call = calls.addObject().put("id", c.id()).put("type", "function");
                            call.putObject("function").put("name", c.name()).put("arguments", c.arguments() == null ? "{}" : c.arguments().toString());
                        }
                    }
                }
                case TOOL -> messages.addObject().put("role", "tool").put("tool_call_id", m.toolCallId()).put("content", m.content());
            }
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.jsonMode()) {
            body.putObject("response_format").put("type", "json_object");
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (LlmTool t : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name()).put("description", t.description());
                fn.set("parameters", t.parameters() == null ? json.createObjectNode().put("type", "object") : t.parameters());
            }
        }
        if (stream) {
            body.put("stream", true);
            body.putObject("stream_options").put("include_usage", true);
        }
        return body;
    }

    /** OpenAI sends arguments as a JSON string; some compatible servers send an object. Unparseable text is kept for the validator to reject. */
    private JsonNode arguments(JsonNode raw) {
        if (raw.isObject()) {
            return raw;
        }
        String text = raw.asText("");
        if (text.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode parsed = json.readTree(text);
            if (parsed != null && parsed.isObject()) {
                return parsed;
            }
        } catch (IOException ignored) {
            // fall through
        }
        return json.createObjectNode().put("_unparsed", text);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asInt() : null;
    }
}
