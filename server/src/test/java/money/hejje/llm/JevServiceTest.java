package money.hejje.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.llm.internal.JevHttpTransport;
import money.hejje.llm.internal.JevStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link JevService} over the HTTP transport against WireMock: answers, retry rules, deadline, circuit, cost cap, SIM cache (plan M9.1). */
class JevServiceTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PATH = "/v1/decisions";
    static WireMockServer wm;

    MutableClock time;
    HejjeClock clock;
    JevStore store;
    AuditService audit;

    static final JevQuestionSet SET = new JevQuestionSet("test", "1", Map.of(
            "urgent", JevQuestion.noul("Is `note` urgent?"),
            "team", JevQuestion.choice("Which team handles `note`?", Map.of("billing", "Payments", "technical", "Bugs")),
            "mood", JevQuestion.score("How frustrated is `note`?", List.of("Calm", "Frustrated", "Very angry"))));

    static final String ANSWER = """
            {"model":"jev-1.13","answers":{
              "urgent":{"type":"noul","noul":0.95},
              "team":{"type":"choice","choice":"billing","probabilities":{"billing":0.88,"technical":0.12},"confidence":0.81},
              "mood":{"type":"score","score":1.05,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.0,"1":0.95,"2":0.05},"confidence":0.92}},
             "usage":{"input_tokens":1000,"output_tokens":20}}
            """;

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
        time = MutableClock.atIst("2026-09-23T10:00:00");
        clock = new HejjeClock(time, MutableClock.IST, (d, e) -> false);
        store = mock(JevStore.class);
        audit = mock(AuditService.class);
        when(store.costSince(any())).thenReturn(BigDecimal.ZERO);
        when(store.findCached(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    JevProperties props(boolean enabled, Duration timeout, BigDecimal cap, int threshold) {
        // HOME is always set, so the transport finds a "key"; WireMock does not check it.
        return new JevProperties(enabled, wm.baseUrl(), "HOME", "jev-1.13", timeout, 5, new BigDecimal("3.6"), cap,
                new LlmProperties.CircuitBreaker(threshold, Duration.ofSeconds(60)), true, 30, null);
    }

    JevService service(JevProperties props, ExecutionMode mode) {
        return new JevService(props, new JevHttpTransport(props, JSON), store, clock, new HejjeProperties(mode, ZoneId.of("Asia/Kolkata"), Path.of(".")), audit,
                JSON);
    }

    JevService service() {
        return service(props(true, Duration.ofMillis(2500), null, 5), ExecutionMode.PAPER);
    }

    static ObjectNode state() {
        return JSON.createObjectNode().put("note", "Payouts failing for 3 days");
    }

    /** The live API echoes {@code jev-latest} for a pinned request (seen 2026-09-24): the answer is recorded under the pinned version. */
    @Test
    void anAliasEchoIsRecordedUnderThePinnedModel() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(ANSWER.replace("\"jev-1.13\"", "\"jev-latest\""))));
        JevResult r = service().evaluate("news", "INFY", state(), SET);
        assertThat(r.ok()).isTrue();
        assertThat(r.model()).isEqualTo("jev-1.13");
    }

    @Test
    void parsesNoulChoiceAndScoreAnswersAndRecordsTheCallWithItsCost() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(ANSWER)));
        JevResult r = service().evaluate("news", "INFY", state(), SET);

        assertThat(r.ok()).isTrue();
        assertThat(r.outcome()).isEqualTo(JevResult.Outcome.OK);
        assertThat(r.model()).isEqualTo("jev-1.13");
        assertThat(r.noul("urgent", 0)).isEqualTo(0.95);
        assertThat(r.answer("urgent").orElseThrow().confidence()).isNull();
        JevAnswer team = r.answer("team").orElseThrow();
        assertThat(team.choice()).isEqualTo("billing");
        assertThat(team.probability()).isEqualTo(0.88);
        assertThat(team.confidence()).isEqualTo(0.81);
        JevAnswer mood = r.answer("mood").orElseThrow();
        assertThat(mood.score()).isEqualTo(1.05);
        assertThat(mood.probabilityOf("1")).isEqualTo(0.95);

        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", containing("Bearer "))
                .withRequestBody(matchingJsonPath("$.model", equalTo("jev-1.13")))
                .withRequestBody(matchingJsonPath("$.state.note", equalTo("Payouts failing for 3 days")))
                .withRequestBody(matchingJsonPath("$.questions.urgent.type", equalTo("noul")))
                .withRequestBody(matchingJsonPath("$.questions.team.criteria.billing", equalTo("Payments")))
                .withRequestBody(matchingJsonPath("$.questions.mood.criteria[2]", equalTo("Very angry"))));

        ArgumentCaptor<JevCall> call = ArgumentCaptor.forClass(JevCall.class);
        verify(store).insert(call.capture(), any());
        JevCall c = call.getValue();
        assertThat(c.purpose()).isEqualTo("news");
        assertThat(c.subject()).isEqualTo("INFY");
        assertThat(c.setName()).isEqualTo("test");
        assertThat(c.outcome()).isEqualTo("OK");
        assertThat(c.inputTokens()).isEqualTo(1000);
        // 1000 tokens × ₹3.6 per million = ₹0.0036 = 0.36 paise
        assertThat(c.costPaise()).isEqualByComparingTo("0.36");
        assertThat(c.answers()).hasSize(3);
    }

    @Test
    void readsAYesNoAnswerGivenAsProbability() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody("{\"answers\":{\"urgent\":{\"type\":\"boolean\",\"probability\":0.7}},\"usage\":{\"input_tokens\":5}}")));
        JevResult r = service().evaluate("t", null, state(), new JevQuestionSet("test", "1", Map.of("urgent", JevQuestion.noul("Urgent?"))));
        assertThat(r.ok()).isTrue();
        assertThat(r.answer("urgent").orElseThrow().type()).isEqualTo("noul");
        assertThat(r.noul("urgent", 0)).isEqualTo(0.7);
        assertThat(r.model()).isEqualTo("jev-1.13");
    }

    @Test
    void retriesOnceAfterA503WhenEnoughTimeIsLeft() {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("r").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("r").whenScenarioStateIs("second").willReturn(aResponse().withStatus(200).withBody(ANSWER)));
        JevResult r = service().evaluate("t", null, state(), SET);
        assertThat(r.ok()).isTrue();
        wm.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void doesNotRetryWhenLessThanHalfTheDeadlineIsLeft() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(529).withFixedDelay(700)));
        JevResult r = service(props(true, Duration.ofMillis(1000), null, 5), ExecutionMode.PAPER).evaluate("t", null, state(), SET);
        assertThat(r.ok()).isFalse();
        assertThat(r.outcome()).isEqualTo(JevResult.Outcome.FAILED);
        assertThat(r.error()).contains("HTTP 529");
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aHungCallReturnsTimeoutAtTheDeadline() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(ANSWER).withFixedDelay(5000)));
        long started = System.nanoTime();
        JevResult r = service(props(true, Duration.ofMillis(300), null, 5), ExecutionMode.PAPER).evaluate("t", null, state(), SET);
        long took = (System.nanoTime() - started) / 1_000_000;
        assertThat(r.outcome()).isEqualTo(JevResult.Outcome.TIMEOUT);
        assertThat(r.answers()).isEmpty();
        assertThat(took).isLessThan(350);
    }

    @Test
    void repeated429sOpenTheCircuitSoLaterCallsDoNotReachTheApi() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)));
        JevService jev = service(props(true, Duration.ofMillis(2500), null, 2), ExecutionMode.PAPER);
        JevResult first = jev.evaluate("t", null, state(), SET); // 429, retry, 429: two failures open the circuit
        assertThat(first.outcome()).isEqualTo(JevResult.Outcome.FAILED);
        JevResult next = jev.evaluate("t", null, state(), SET);
        assertThat(next.outcome()).isEqualTo(JevResult.Outcome.CIRCUIT_OPEN);
        wm.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(jev.status().circuit()).isEqualTo("OPEN");
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(1)).record(events.capture());
        assertThat(events.getValue().type()).isEqualTo(AuditEventType.JEV_CIRCUIT_OPEN);
    }

    @Test
    void aRefusedRequestIsNotRetriedAndDoesNotCountTowardsTheCircuit() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(422).withBody("{\"detail\":\"criteria missing\"}")));
        JevService jev = service(props(true, Duration.ofMillis(2500), null, 1), ExecutionMode.PAPER);
        for (int i = 0; i < 3; i++) {
            JevResult r = jev.evaluate("t", null, state(), SET);
            assertThat(r.outcome()).isEqualTo(JevResult.Outcome.FAILED);
            assertThat(r.error()).contains("HTTP 422").contains("criteria missing");
        }
        wm.verify(3, postRequestedFor(urlEqualTo(PATH)));
        assertThat(jev.status().circuit()).isEqualTo("CLOSED");
    }

    @Test
    void theDailyCostCapStopsCallsAndAlertsOnce() {
        when(store.costSince(any())).thenReturn(new BigDecimal("100.0000"));
        JevService jev = service(props(true, Duration.ofMillis(2500), new BigDecimal("1"), 5), ExecutionMode.PAPER);
        assertThat(jev.evaluate("t", null, state(), SET).outcome()).isEqualTo(JevResult.Outcome.BUDGET);
        assertThat(jev.evaluate("t", null, state(), SET).outcome()).isEqualTo(JevResult.Outcome.BUDGET);
        wm.verify(0, postRequestedFor(urlEqualTo(PATH)));
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(1)).record(events.capture());
        assertThat(events.getValue().type()).isEqualTo(AuditEventType.JEV_BUDGET_EXCEEDED);
    }

    @Test
    void disabledReturnsWithoutCallingOrRecording() {
        JevResult r = service(props(false, Duration.ofMillis(2500), null, 5), ExecutionMode.PAPER).evaluate("t", null, state(), SET);
        assertThat(r.outcome()).isEqualTo(JevResult.Outcome.DISABLED);
        assertThat(r.callId()).isNull();
        wm.verify(0, postRequestedFor(urlEqualTo(PATH)));
        verify(store, never()).insert(any(), any());
    }

    @Test
    void tooManyQuestionsIsAProgrammingError() {
        JevQuestionSet big = SET.with(Map.of("a", JevQuestion.noul("a?"), "b", JevQuestion.noul("b?"), "c", JevQuestion.noul("c?")));
        assertThatThrownBy(() -> service().evaluate("t", null, state(), big)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the limit is 5");
    }

    @Test
    void nonFiniteNumbersInTheStateAreSentAsNull() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(ANSWER)));
        ObjectNode s = state();
        s.put("rvol", Double.NaN);
        s.putArray("returns").add(1.5).add(Double.POSITIVE_INFINITY);
        assertThat(service().evaluate("t", null, s, SET).ok()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("\"rvol\":null")).withRequestBody(containing("\"returns\":[1.5,null]")));
    }

    @Test
    void inSimTheSameStateIsAnsweredFromTheStore() {
        JevAnswer stored = new JevAnswer("urgent", "noul", null, null, 0.9, Map.of(), null);
        when(store.findCached(anyString(), anyString(), anyString())).thenReturn(Optional.of(new JevStore.Cached("jev-1.13", 900, Map.of("urgent", stored))));
        JevResult r = service(props(true, Duration.ofMillis(2500), null, 5), ExecutionMode.SIM).evaluate("t", null, state(), SET);
        assertThat(r.ok()).isTrue();
        assertThat(r.outcome()).isEqualTo(JevResult.Outcome.CACHED);
        assertThat(r.noul("urgent", 0)).isEqualTo(0.9);
        wm.verify(0, postRequestedFor(urlEqualTo(PATH)));
        ArgumentCaptor<JevCall> call = ArgumentCaptor.forClass(JevCall.class);
        verify(store).insert(call.capture(), any());
        assertThat(call.getValue().outcome()).isEqualTo("CACHED");
        assertThat(call.getValue().costPaise()).isEqualByComparingTo("0");
    }

    @Test
    void outsideSimTheStoreIsNotConsulted() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(ANSWER)));
        service().evaluate("t", null, state(), SET);
        verify(store, never()).findCached(anyString(), anyString(), anyString());
    }
}
