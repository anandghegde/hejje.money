package money.hejje.llm.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
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

/** Contract test of the native Gemini adapter against recorded response shapes (test/resources/llm/gemini). */
class GeminiProviderContractTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String GENERATE = "/v1beta/models/gemini-test:generateContent";
    static final String STREAM = "/v1beta/models/gemini-test:streamGenerateContent?alt=sse";
    static WireMockServer wm;

    GeminiProvider provider;

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
        provider = new GeminiProvider("gemini", new LlmProperties.Provider("gemini", wm.baseUrl() + "/v1beta", KEY_ENV, "models/gemini-test",
                Duration.ofSeconds(5)), JSON);
    }

    static LlmTool regimeTool() throws Exception {
        return new LlmTool("get_market_regime", "Current market regime",
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"detail\":{\"type\":[\"boolean\",\"null\"]}},\"additionalProperties\":false}"));
    }

    @Test
    void generateSendsSystemInstructionContentsAndJsonModeAndMapsUsage() {
        wm.stubFor(post(urlEqualTo(GENERATE)).willReturn(okJson(fixture("gemini/generate.json"))));
        LlmResponse r = provider.complete(new LlmRequest("news", "t", "v1", "Answer in JSON.", "Is it ok?", 50, 0.0, true), null);
        assertThat(r.text()).isEqualTo("{\"ok\": true}");
        assertThat(r.model()).isEqualTo("gemini-test-001");
        assertThat(r.inputTokens()).isEqualTo(12);
        assertThat(r.outputTokens()).isEqualTo(5);
        assertThat(r.finishReason()).isEqualTo("STOP");
        wm.verify(postRequestedFor(urlEqualTo(GENERATE))
                .withHeader("x-goog-api-key", equalTo(System.getenv(KEY_ENV)))
                .withRequestBody(matchingJsonPath("$.systemInstruction.parts[0].text", equalTo("Answer in JSON.")))
                .withRequestBody(matchingJsonPath("$.contents[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", equalTo("Is it ok?")))
                .withRequestBody(matchingJsonPath("$.generationConfig.maxOutputTokens", equalTo("50")))
                .withRequestBody(matchingJsonPath("$.generationConfig.responseMimeType", equalTo("application/json"))));
    }

    @Test
    void functionCallRoundTripWithSanitisedSchemas() throws Exception {
        wm.stubFor(post(urlEqualTo(GENERATE)).atPriority(5).willReturn(okJson(fixture("gemini/function_call.json"))));
        wm.stubFor(post(urlEqualTo(GENERATE)).atPriority(1).withRequestBody(matchingJsonPath("$.contents[2].parts[0].functionResponse"))
                .willReturn(okJson(fixture("gemini/function_answer.json"))));
        List<LlmTool> tools = List.of(regimeTool());
        List<LlmMessage> convo = new ArrayList<>(List.of(LlmMessage.user("What is the regime?")));

        LlmResponse first = provider.complete(LlmRequest.chat("reasoning", "chat", "v1", "sys", convo, tools), null);
        assertThat(first.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("gemini_call_0");
            assertThat(c.name()).isEqualTo("get_market_regime");
            assertThat(c.arguments().path("detail").asBoolean()).isTrue();
        });

        convo.add(LlmMessage.assistant(first.text(), first.toolCalls()));
        convo.add(LlmMessage.tool(first.toolCalls().get(0).id(), "get_market_regime", "{\"trend\":\"UP\"}"));
        LlmResponse second = provider.complete(LlmRequest.chat("reasoning", "chat", "v1", "sys", convo, tools), null);
        assertThat(second.text()).contains("trending up");
        wm.verify(postRequestedFor(urlEqualTo(GENERATE))
                .withRequestBody(matchingJsonPath("$.tools[0].functionDeclarations[0].name", equalTo("get_market_regime")))
                .withRequestBody(matchingJsonPath("$.tools[0].functionDeclarations[0].parameters.properties.detail.type", equalTo("boolean")))
                .withRequestBody(matchingJsonPath("$.tools[0].functionDeclarations[0].parameters.properties.detail.nullable", equalTo("true")))
                .withRequestBody(notContaining("additionalProperties"))
                .withRequestBody(matchingJsonPath("$.contents[1].role", equalTo("model")))
                .withRequestBody(matchingJsonPath("$.contents[1].parts[0].functionCall.name", equalTo("get_market_regime")))
                .withRequestBody(matchingJsonPath("$.contents[2].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.contents[2].parts[0].functionResponse.name", equalTo("get_market_regime")))
                .withRequestBody(matchingJsonPath("$.contents[2].parts[0].functionResponse.response.trend", equalTo("UP"))));
    }

    @Test
    void streamedResponseArrivesAsDeltasWithUsageOnTheLastChunk() throws Throwable {
        wm.stubFor(post(urlEqualTo(STREAM)).willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(fixture("gemini/stream.sse"))));
        List<LlmChunk> chunks = drain(provider.stream(new LlmRequest("fast", "t", "v1", null, "Say hello", null, null, false), null));
        assertThat(chunks.stream().filter(c -> !c.last()).map(LlmChunk::delta).collect(Collectors.toList())).containsExactly("Hello", " world");
        LlmResponse r = chunks.get(chunks.size() - 1).response();
        assertThat(r.text()).isEqualTo("Hello world");
        assertThat(r.inputTokens()).isEqualTo(6);
        assertThat(r.outputTokens()).isEqualTo(2);
        assertThat(r.finishReason()).isEqualTo("STOP");
    }

    @Test
    void blockedPromptsAndErrorsMapToLlmExceptions() {
        wm.stubFor(post(urlEqualTo(GENERATE)).willReturn(okJson(fixture("gemini/blocked.json"))));
        assertThatThrownBy(() -> provider.complete(new LlmRequest("fast", "t", "v1", null, "x", null, null, false), null))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("blocked").contains("SAFETY");
                });
        wm.stubFor(post(urlEqualTo(GENERATE)).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> provider.complete(new LlmRequest("fast", "t", "v1", null, "x", null, null, false), null))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRetryable()).isTrue());
    }
}
