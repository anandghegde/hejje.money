package money.hejje.llm.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static money.hejje.llm.internal.ProviderContractSupport.KEY_ENV;
import static money.hejje.llm.internal.ProviderContractSupport.drain;
import static money.hejje.llm.internal.ProviderContractSupport.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import money.hejje.llm.LlmChunk;
import money.hejje.llm.LlmException;
import money.hejje.llm.LlmMessage;
import money.hejje.llm.LlmProperties;
import money.hejje.llm.LlmRequest;
import money.hejje.llm.LlmResponse;
import money.hejje.llm.LlmTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contract test of the OpenAI chat-completions adapter against recorded response shapes (test/resources/llm/openai). */
class OpenAiCompatibleProviderContractTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PATH = "/v1/chat/completions";
    static WireMockServer wm;

    OpenAiCompatibleProvider provider;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        provider = new OpenAiCompatibleProvider("primary", new LlmProperties.Provider("openai-compatible", wm.baseUrl() + "/v1/", KEY_ENV, "gpt-test",
                Duration.ofSeconds(5)), JSON);
    }

    static LlmTool regimeTool() throws Exception {
        return new LlmTool("get_market_regime", "Current market regime",
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"detail\":{\"type\":\"boolean\"}},\"additionalProperties\":false}"));
    }

    @Test
    void completionSendsPromptsAndParametersAndMapsTextAndUsage() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(fixture("openai/completion.json"))));
        LlmResponse r = provider.complete(new LlmRequest("fast", "t", "v1", "You are terse.", "Say hi", 20, 0.0, true), null);
        assertThat(r.text()).isEqualTo("Hi.");
        assertThat(r.model()).isEqualTo("gpt-test-2026-01-01");
        assertThat(r.inputTokens()).isEqualTo(17);
        assertThat(r.outputTokens()).isEqualTo(2);
        assertThat(r.finishReason()).isEqualTo("stop");
        assertThat(r.toolCalls()).isEmpty();
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + System.getenv(KEY_ENV)))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-test")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("You are terse.")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("Say hi")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("20")))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object"))));
    }

    @Test
    void toolCallRoundTrip() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).atPriority(5).willReturn(okJson(fixture("openai/tool_call.json"))));
        wm.stubFor(post(urlEqualTo(PATH)).atPriority(1).withRequestBody(matchingJsonPath("$.messages[?(@.role == 'tool')]"))
                .willReturn(okJson(fixture("openai/tool_answer.json"))));
        List<LlmTool> tools = List.of(regimeTool());
        List<LlmMessage> convo = new ArrayList<>(List.of(LlmMessage.user("What is the regime?")));

        LlmResponse first = provider.complete(LlmRequest.chat("reasoning", "chat", "v1", "sys", convo, tools), null);
        assertThat(first.finishReason()).isEqualTo("tool_calls");
        assertThat(first.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("call_abc123");
            assertThat(c.name()).isEqualTo("get_market_regime");
            assertThat(c.arguments().path("detail").asBoolean()).isTrue();
        });

        convo.add(LlmMessage.assistant(first.text(), first.toolCalls()));
        convo.add(LlmMessage.tool("call_abc123", "get_market_regime", "{\"trend\":\"UP\"}"));
        LlmResponse second = provider.complete(LlmRequest.chat("reasoning", "chat", "v1", "sys", convo, tools), null);
        assertThat(second.text()).contains("trending up").contains("[get_market_regime]");
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.tools[0].type", equalTo("function")))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("get_market_regime")))
                .withRequestBody(matchingJsonPath("$.messages[2].role", equalTo("assistant")))
                .withRequestBody(matchingJsonPath("$.messages[2].tool_calls[0].id", equalTo("call_abc123")))
                .withRequestBody(matchingJsonPath("$.messages[2].tool_calls[0].function.arguments", equalTo("{\"detail\":true}")))
                .withRequestBody(matchingJsonPath("$.messages[3].tool_call_id", equalTo("call_abc123")))
                .withRequestBody(matchingJsonPath("$.messages[3].content", equalTo("{\"trend\":\"UP\"}"))));
    }

    @Test
    void streamedTextArrivesAsDeltasWithUsageOnTheLastChunk() throws Throwable {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(fixture("openai/stream.sse"))));
        List<LlmChunk> chunks = drain(provider.stream(new LlmRequest("fast", "t", "v1", null, "Say hello", null, null, false), null));
        assertThat(chunks.stream().filter(c -> !c.last()).map(LlmChunk::delta).collect(Collectors.toList())).containsExactly("Hello", " world");
        LlmChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.last()).isTrue();
        assertThat(last.response().text()).isEqualTo("Hello world");
        assertThat(last.response().inputTokens()).isEqualTo(9);
        assertThat(last.response().outputTokens()).isEqualTo(2);
        assertThat(last.response().finishReason()).isEqualTo("stop");
        wm.verify(postRequestedFor(urlEqualTo(PATH)).withHeader("Accept", equalTo("text/event-stream"))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.stream_options.include_usage", equalTo("true"))));
    }

    @Test
    void streamedToolCallArgumentsAreAssembledFromFragments() throws Throwable {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(fixture("openai/stream_tool.sse"))));
        List<LlmChunk> chunks = drain(provider.stream(LlmRequest.chat("reasoning", "chat", "v1", null, List.of(LlmMessage.user("NIFTY?")), List.of(regimeTool())), null));
        LlmResponse r = chunks.get(chunks.size() - 1).response();
        assertThat(r.finishReason()).isEqualTo("tool_calls");
        assertThat(r.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("call_s1");
            assertThat(c.name()).isEqualTo("get_market_snapshot");
            assertThat(c.arguments().path("instrument").asText()).isEqualTo("NSE:NIFTY 50");
        });
    }

    @Test
    void rateLimitsAndServerErrorsAreRetryableClientErrorsAreNot() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> provider.complete(new LlmRequest("fast", "t", "v1", null, "x", null, null, false), null))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRetryable()).isTrue());
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400).withBody("{\"error\":{\"message\":\"bad model\"}}")));
        assertThatThrownBy(() -> provider.complete(new LlmRequest("fast", "t", "v1", null, "x", null, null, false), null))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("HTTP 400").contains("bad model");
                });
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> drain(provider.stream(new LlmRequest("fast", "t", "v1", null, "x", null, null, false), null)))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRetryable()).isTrue());
    }
}
