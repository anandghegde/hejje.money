package money.hejje.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.llm.internal.LlmCallStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Circuit breaker, fallback profile, daily cost cap, JSON-mode temperature, streaming and tool calls through {@link LlmService}. */
class LlmResilienceTest {

    static final ObjectMapper JSON = new ObjectMapper();

    MutableClock time;
    HejjeClock clock;
    LlmCallStore store;
    AuditService audit;

    @BeforeEach
    void setUp() {
        time = MutableClock.atIst("2026-09-08T10:00:00");
        clock = new HejjeClock(time, MutableClock.IST, (d, e) -> false);
        store = mock(LlmCallStore.class);
        audit = mock(AuditService.class);
        when(store.usageSince(any())).thenReturn(LlmUsage.NONE);
    }

    static LlmProperties props(Map<String, LlmProperties.Profile> profiles, BigDecimal cap, int threshold) {
        Map<String, LlmProperties.Provider> providers = Map.of("p", new LlmProperties.Provider("fixture", null, null, "m", Duration.ofSeconds(1)));
        return new LlmProperties(true, providers, profiles, 0, Duration.ZERO, Map.of("m", new LlmProperties.Pricing(100.0, 300.0)), cap,
                new LlmProperties.CircuitBreaker(threshold, Duration.ofSeconds(60)));
    }

    static LlmProperties.Profile profile(String provider, String fallback) {
        return new LlmProperties.Profile(provider, "m", null, null, fallback);
    }

    static final class Failing implements LlmProvider {
        final String name;
        int calls;
        boolean fail = true;
        boolean retryable = true;

        Failing(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public LlmResponse complete(LlmRequest request, String model) {
            calls++;
            if (fail) {
                throw new LlmException("HTTP 503 from " + name, retryable);
            }
            return new LlmResponse("ok", name, model, 1, 1, 1);
        }
    }

    static LlmRequest request(String profile) {
        return new LlmRequest(profile, "t", "v1", null, "x", null, null, false);
    }

    @Test
    void circuitOpensAfterConsecutiveRetryableFailuresThenLetsOneTrialThroughAfterTheCooldown() {
        Failing p = new Failing("p");
        LlmService llm = new LlmService(props(Map.of("fast", profile("p", null)), null, 3), List.of(p), store, clock, audit, e -> { });
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> llm.complete(request("fast"))).hasMessageContaining("HTTP 503");
        }
        assertThatThrownBy(() -> llm.complete(request("fast"))).isInstanceOf(LlmException.Unavailable.class).hasMessageContaining("circuit open");
        assertThat(p.calls).isEqualTo(3);
        assertThat(llm.status().providers().get(0).circuit()).isEqualTo("OPEN");

        time.advance(Duration.ofSeconds(61));
        assertThat(llm.status().providers().get(0).circuit()).isEqualTo("HALF_OPEN");
        assertThatThrownBy(() -> llm.complete(request("fast"))).hasMessageContaining("HTTP 503"); // the trial reaches the provider and fails
        assertThat(p.calls).isEqualTo(4);
        assertThatThrownBy(() -> llm.complete(request("fast"))).hasMessageContaining("circuit open");

        time.advance(Duration.ofSeconds(61));
        p.fail = false;
        assertThat(llm.complete(request("fast")).text()).isEqualTo("ok");
        LlmService.ProviderStatus status = llm.status().providers().get(0);
        assertThat(status.circuit()).isEqualTo("CLOSED");
        assertThat(status.consecutiveFailures()).isZero();
        assertThat(status.lastError()).contains("HTTP 503");
    }

    @Test
    void nonRetryableFailuresMeanTheProviderAnsweredAndNeverOpenTheCircuit() {
        Failing p = new Failing("p");
        p.retryable = false;
        LlmService llm = new LlmService(props(Map.of("fast", profile("p", null)), null, 2), List.of(p), store, clock, audit, e -> { });
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> llm.complete(request("fast"))).hasMessageContaining("HTTP 503");
        }
        assertThat(p.calls).isEqualTo(5);
    }

    @Test
    void fallbackProfileAnswersWhenThePrimaryProviderFails() {
        Failing bad = new Failing("bad");
        FixtureLlmProvider fx = new FixtureLlmProvider("fx");
        fx.respondWhenContains("x", "from fallback");
        LlmService llm = new LlmService(props(Map.of("reasoning", profile("bad", "fast"), "fast", profile("fx", null)), null, 5), List.of(bad, fx), store, clock,
                audit, e -> { });
        LlmResponse r = llm.complete(request("reasoning"));
        assertThat(r.text()).isEqualTo("from fallback");
        assertThat(r.provider()).isEqualTo("fx");
        ArgumentCaptor<LlmCall> rows = ArgumentCaptor.forClass(LlmCall.class);
        verify(store, times(2)).insert(rows.capture());
        assertThat(rows.getAllValues()).extracting(LlmCall::profile, LlmCall::provider, LlmCall::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("reasoning", "bad", "FAILED"), org.assertj.core.groups.Tuple.tuple("fast", "fx", "OK"));
    }

    @Test
    void dailyCostCapDisablesCallsUntilTheNextDayAndAlertsOnce() {
        FixtureLlmProvider fx = new FixtureLlmProvider("p");
        fx.fallback(r -> "fine");
        when(store.costSince(any())).thenReturn(1_000L);
        LlmService llm = new LlmService(props(Map.of("fast", profile("p", null)), new BigDecimal("10"), 5), List.of(fx), store, clock, audit, e -> { });
        assertThatThrownBy(() -> llm.complete(request("fast"))).isInstanceOf(LlmException.BudgetExceeded.class).hasMessageContaining("1000 of 1000 paise");
        assertThatThrownBy(() -> llm.complete(request("fast"))).isInstanceOf(LlmException.BudgetExceeded.class);
        assertThat(fx.requests()).isEmpty();
        verify(audit, times(1)).record(argThat(e -> e.type() == AuditEventType.LLM_BUDGET_EXCEEDED));
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).costSince(from.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-09-07T18:30:00Z")); // IST midnight

        when(store.usageSince(any())).thenReturn(new LlmUsage(3, 0, 10, 10, 1_000));
        LlmService.Status status = llm.status();
        assertThat(status.dailyCostCapPaise()).isEqualTo(1_000L);
        assertThat(status.budgetExceeded()).isTrue();

        when(store.costSince(any())).thenReturn(999L);
        assertThat(llm.complete(request("fast")).text()).isEqualTo("fine");
    }

    @Test
    void jsonModeDefaultsTemperatureToZeroUnlessTheProfileOrRequestSetsOne() {
        FixtureLlmProvider fx = new FixtureLlmProvider("p");
        fx.fallback(r -> "{}");
        LlmService llm = new LlmService(props(Map.of("fast", profile("p", null)), null, 5), List.of(fx), store, clock, audit, e -> { });
        llm.complete(new LlmRequest("fast", "t", "v1", null, "x", null, null, true));
        llm.complete(new LlmRequest("fast", "t", "v1", null, "x", null, null, false));
        llm.complete(new LlmRequest("fast", "t", "v1", null, "x", null, 0.7, true));
        assertThat(fx.requests()).extracting(LlmRequest::temperature).containsExactly(0.0, null, 0.7);
    }

    @Test
    void streamForwardsDeltasAndReturnsTheFullResponse() {
        FixtureLlmProvider fx = new FixtureLlmProvider("p");
        fx.respondWhenContains("x", "alpha beta gamma");
        LlmService llm = new LlmService(props(Map.of("fast", profile("p", null)), null, 5), List.of(fx), store, clock, audit, e -> { });
        List<String> deltas = new ArrayList<>();
        LlmResponse r = llm.stream(request("fast"), deltas::add);
        assertThat(deltas).containsExactly("alpha ", "beta ", "gamma");
        assertThat(r.text()).isEqualTo("alpha beta gamma");
        verify(store, times(1)).insert(argThat(c -> "OK".equals(c.status())));
    }

    @Test
    void scriptedToolCallsRoundTripThroughAConversation() {
        FixtureLlmProvider fx = new FixtureLlmProvider("p");
        fx.responder(r -> r.messages().size() == 1 ? FixtureLlmProvider.calls(new LlmToolCall("c1", "get_market_regime", JSON.createObjectNode()))
                : FixtureLlmProvider.text("Trending up."));
        LlmService llm = new LlmService(props(Map.of("fast", profile("p", null)), null, 5), List.of(fx), store, clock, audit, e -> { });
        List<LlmTool> tools = List.of(new LlmTool("get_market_regime", "Current regime", null));
        List<LlmMessage> convo = new ArrayList<>(List.of(LlmMessage.user("What is the regime?")));
        LlmResponse first = llm.complete(LlmRequest.chat("fast", "chat", "v1", "sys", convo, tools));
        assertThat(first.hasToolCalls()).isTrue();
        assertThat(first.finishReason()).isEqualTo("tool_calls");
        convo.add(LlmMessage.assistant(first.text(), first.toolCalls()));
        convo.add(LlmMessage.tool("c1", "get_market_regime", "{\"trend\":\"UP\"}"));
        LlmResponse second = llm.complete(LlmRequest.chat("fast", "chat", "v1", "sys", convo, tools));
        assertThat(second.text()).isEqualTo("Trending up.");
        assertThat(fx.requests().get(1).userPrompt()).isEqualTo("What is the regime?");
        assertThat(fx.requests().get(0).promptText()).isNotEqualTo(fx.requests().get(1).promptText());
    }
}
