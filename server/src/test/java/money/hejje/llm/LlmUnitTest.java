package money.hejje.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.llm.internal.LlmCallStore;
import org.junit.jupiter.api.Test;

class LlmUnitTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HejjeClock CLOCK = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);

    static LlmProperties props(boolean enabled, int retries) {
        return new LlmProperties(enabled, Map.of("fx", new LlmProperties.Provider("fixture", null, null, "m", Duration.ofSeconds(1))),
                Map.of("news", new LlmProperties.Profile("fx", "fixture-news", 0.0, 100, null)), retries, Duration.ofMillis(1),
                Map.of("fixture-news", new LlmProperties.Pricing(100.0, 300.0)), null, null);
    }

    @Test
    void disabledLlmIsUnavailableAndLogsNothing() {
        LlmCallStore store = mock(LlmCallStore.class);
        LlmService llm = new LlmService(props(false, 0), List.of(new FixtureLlmProvider("fx")), store, CLOCK, mock(money.hejje.audit.AuditService.class), e -> { });
        assertThat(llm.enabled()).isFalse();
        assertThatThrownBy(() -> llm.complete(new LlmRequest("news", "t", "v1", null, "hello", null, null, false))).isInstanceOf(LlmException.Unavailable.class);
        verify(store, never()).insert(any());
    }

    @Test
    void completesThroughTheProfileAndLogsTokensAndCostWithoutThePrompt() {
        LlmCallStore store = mock(LlmCallStore.class);
        FixtureLlmProvider fx = new FixtureLlmProvider("fx");
        fx.respondTo("sys", "hello", "world");
        LlmService llm = new LlmService(props(true, 0), List.of(fx), store, CLOCK, mock(money.hejje.audit.AuditService.class), e -> { });
        LlmResponse r = llm.complete(new LlmRequest("news", "test", "v1", "sys", "hello", null, null, false));
        assertThat(r.text()).isEqualTo("world");
        assertThat(r.model()).isEqualTo("fixture-news");
        org.mockito.ArgumentCaptor<LlmCall> call = org.mockito.ArgumentCaptor.forClass(LlmCall.class);
        verify(store).insert(call.capture());
        assertThat(call.getValue().status()).isEqualTo("OK");
        assertThat(call.getValue().promptHash()).hasSize(16).isEqualTo(Prompts.hash("sys", "hello"));
        assertThat(call.getValue().purpose()).isEqualTo("test");
        assertThat(call.getValue().promptVersion()).isEqualTo("v1");
        assertThat(call.getValue().costEstimatePaise()).isEqualTo(Math.round((2 * 100.0 / 1_000_000 + 1 * 300.0 / 1_000_000) * 100));
        assertThat(call.getValue().toString()).doesNotContain("hello").doesNotContain("sys");
        assertThat(fx.requests().get(0).maxTokens()).isEqualTo(100); // profile defaults applied
    }

    @Test
    void retriesRetryableFailuresWithBackoffAndGivesUp() {
        LlmCallStore store = mock(LlmCallStore.class);
        LlmProvider flaky = new LlmProvider() {
            int calls;
            @Override public String name() { return "fx"; }
            @Override public LlmResponse complete(LlmRequest request, String model) {
                if (++calls < 3) {
                    throw new LlmException("HTTP 429", true);
                }
                return new LlmResponse("ok", "fx", model, 1, 1, 1);
            }
        };
        LlmService llm = new LlmService(props(true, 2), List.of(flaky), store, CLOCK, mock(money.hejje.audit.AuditService.class), e -> { });
        assertThat(llm.complete(new LlmRequest("news", "t", "v1", null, "x", null, null, false)).text()).isEqualTo("ok");
        verify(store, org.mockito.Mockito.times(3)).insert(any());
        LlmService once = new LlmService(props(true, 0), List.of(new FixtureLlmProvider("fx")), store, CLOCK, mock(money.hejje.audit.AuditService.class), e -> { });
        assertThatThrownBy(() -> once.complete(new LlmRequest("news", "t", "v1", null, "unknown", null, null, false)))
                .isInstanceOf(LlmException.class).hasMessageContaining("No fixture response");
    }

    @Test
    void structuredOutputValidatesAndRetriesOnce() throws Exception {
        LlmCallStore store = mock(LlmCallStore.class);
        FixtureLlmProvider fx = new FixtureLlmProvider("fx");
        fx.respondWhenContains("previous answer was invalid", "{\"score\": 0.5, \"label\": \"BULLISH\"}");
        fx.respondWhenContains("rate this", "Sure! {\"score\": 2, \"label\": \"BULLISH\"}");
        LlmService llm = new LlmService(props(true, 0), List.of(fx), store, CLOCK, mock(money.hejje.audit.AuditService.class), e -> { });
        StructuredOutput structured = new StructuredOutput(llm, JSON);
        JsonNode schema = JSON.readTree("{\"type\":\"object\",\"required\":[\"score\",\"label\"],\"properties\":{\"score\":{\"type\":\"number\",\"minimum\":-1,\"maximum\":1},"
                + "\"label\":{\"type\":\"string\",\"enum\":[\"BULLISH\",\"BEARISH\"]}}}");
        JsonNode answer = structured.ask("news", "t", "v1", null, "rate this", schema);
        assertThat(answer.get("score").asDouble()).isEqualTo(0.5);
        assertThat(fx.requests()).hasSize(2);
        assertThat(fx.requests().get(1).userPrompt()).contains("$.score: above maximum 1");
        // invalid twice -> exception
        fx.reset();
        fx.fallback(r -> "{\"label\": \"MAYBE\"}");
        assertThatThrownBy(() -> structured.ask("news", "t", "v1", null, "rate this", schema)).isInstanceOf(LlmException.class)
                .hasMessageContaining("missing score").hasMessageContaining("not one of");
    }

    @Test
    void schemaLiteCoversTypesEnumsRangesRequiredAndArrays() throws Exception {
        JsonNode schema = JSON.readTree("{\"type\":\"object\",\"required\":[\"a\"],\"properties\":{\"a\":{\"type\":\"array\",\"items\":{\"type\":\"object\","
                + "\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":0},\"k\":{\"enum\":[\"x\",\"y\"]}}}}}}");
        assertThat(JsonSchemaLite.validate(schema, JSON.readTree("{\"a\":[{\"n\":1,\"k\":\"x\"}]}"), "$")).isEmpty();
        assertThat(JsonSchemaLite.validate(schema, JSON.readTree("{\"a\":[{\"n\":-1,\"k\":\"z\"},{}]}"), "$"))
                .containsExactly("$.a[0].n: below minimum 0", "$.a[0].k: not one of [\"x\",\"y\"]", "$.a[1]: missing n");
        assertThat(JsonSchemaLite.validate(schema, JSON.readTree("{\"a\":\"nope\"}"), "$")).containsExactly("$.a: expected array");
        assertThat(JsonSchemaLite.validate(schema, JSON.readTree("[]"), "$")).containsExactly("$: expected object");
        assertThat(Prompts.fill("Hi {{name}}, {{x}}", Map.of("name", "A"))).isEqualTo("Hi A, {{x}}");
        assertThat(Instant.now()).isNotNull();
    }
}
