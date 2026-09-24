package money.hejje.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import money.hejje.AbstractIntegrationTest;
import money.hejje.llm.internal.JevStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Jev through the Spring context with {@link FixtureJev}: evaluate, the stored call, status, scopes (plan M9.1). */
class JevIT extends AbstractIntegrationTest {

    @Autowired
    JevTransport transport;

    @Autowired
    JevService jev;

    @Autowired
    JevQuestionSets sets;

    @Autowired
    JevStore store;

    @AfterEach
    void resetFixture() {
        ((FixtureJev) transport).reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluatesASetStoresTheAnswersAndReportsUsage() {
        FixtureJev fx = (FixtureJev) transport;
        fx.noul("urgent", 0.83);
        String admin = adminAccessToken();

        ResponseEntity<Map> r = rest.exchange("/api/v1/jev/evaluate", HttpMethod.POST,
                new HttpEntity<>(Map.of("set", "sample", "state", Map.of("note", "Payouts failing for 3 days")), bearer(admin)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("ok", true).containsEntry("outcome", "OK").containsEntry("model", "fixture-jev");
        Map<String, Map<String, Object>> answers = (Map<String, Map<String, Object>>) r.getBody().get("answers");
        assertThat(answers.get("urgent")).containsEntry("noul", 0.83);
        assertThat(answers.get("team")).containsEntry("choice", "billing");
        assertThat(answers.get("mood")).containsEntry("score", 0.0);

        ResponseEntity<List> calls = rest.exchange("/api/v1/jev/calls?purpose=manual&limit=5", HttpMethod.GET, new HttpEntity<>(bearer(admin)), List.class);
        Map<String, Object> call = (Map<String, Object>) calls.getBody().get(0);
        assertThat(call).containsEntry("setName", "sample").containsEntry("setVersion", "1").containsEntry("outcome", "OK");
        assertThat((List<?>) call.get("answers")).hasSize(3);

        ResponseEntity<Map> status = rest.exchange("/api/v1/jev/status", HttpMethod.GET, new HttpEntity<>(bearer(admin)), Map.class);
        assertThat(status.getBody()).containsEntry("enabled", true).containsEntry("fixture", true).containsEntry("circuit", "CLOSED");
        assertThat(((Number) ((Map<String, Object>) status.getBody().get("today")).get("ok")).longValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inlineQuestionsWorkAndOnlyAdminsMayEvaluate() {
        String admin = adminAccessToken();
        Map<String, Object> body = Map.of("state", Map.of("text", "hello"),
                "questions", Map.of("greeting", Map.of("type", "noul", "instructions", "Is `text` a greeting?")));
        ResponseEntity<Map> r = rest.exchange("/api/v1/jev/evaluate", HttpMethod.POST, new HttpEntity<>(body, bearer(admin)), Map.class);
        assertThat(r.getBody()).containsEntry("ok", true);
        assertThat(((Map<String, Map<String, Object>>) r.getBody().get("answers")).get("greeting")).containsEntry("noul", 0.5);

        ResponseEntity<Map> created = rest.postForEntity("/api/v1/auth/clients",
                new HttpEntity<>(Map.of("name", "jev-it-reader", "scopes", List.of("market:read")), bearer(admin)), Map.class);
        String key = (String) created.getBody().get("key");
        assertThat(rest.exchange("/api/v1/jev/status", HttpMethod.GET, new HttpEntity<>(bearer(key)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/v1/jev/evaluate", HttpMethod.POST, new HttpEntity<>(body, bearer(key)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aFailingJevIsAResultNotAnException() {
        ((FixtureJev) transport).failWith(new LlmException("HTTP 422 from jev: bad", false));
        JevResult r = jev.evaluate("it", "INFY", new ObjectMapper().createObjectNode().put("note", "x"), sets.get("sample"));
        assertThat(r.ok()).isFalse();
        assertThat(r.outcome()).isEqualTo(JevResult.Outcome.FAILED);
        assertThat(r.callId()).isNotNull();
        assertThat(jev.recentCalls("it", "INFY", 1)).singleElement().satisfies(c -> {
            assertThat(c.outcome()).isEqualTo("FAILED");
            assertThat(c.error()).contains("HTTP 422");
        });
    }

    @Test
    void theStoredAnswersOfAStateAreFoundForTheSimCache() {
        ((FixtureJev) transport).noul("urgent", 0.61);
        JevResult r = jev.evaluate("it-cache", null, new ObjectMapper().createObjectNode().put("note", "cache me"), sets.get("sample"));
        assertThat(r.ok()).isTrue();
        JevCall call = jev.recentCalls("it-cache", null, 1).get(0);
        JevStore.Cached cached = store.findCached(call.stateHash(), "sample", "1").orElseThrow();
        assertThat(cached.model()).isEqualTo("fixture-jev");
        assertThat(cached.answers()).containsOnlyKeys("urgent", "team", "mood");
        assertThat(cached.answers().get("urgent").noul()).isEqualTo(0.61);
        assertThat(cached.answers().get("team").probabilityOf("billing")).isEqualTo(1.0);
        assertThat(store.findCached(call.stateHash(), "sample", "2")).isEmpty();
    }

    @Test
    void pruningDropsOldStatesButKeepsAnswers() {
        JevResult r = jev.evaluate("it-prune", null, new ObjectMapper().createObjectNode().put("note", "y"), sets.get("sample"));
        assertThat(r.ok()).isTrue();
        assertThat(store.pruneStates(jev.recentCalls("it-prune", null, 1).get(0).at().plusSeconds(1))).isGreaterThanOrEqualTo(1);
        assertThat(jev.recentCalls("it-prune", null, 1).get(0).answers()).hasSize(3);
    }
}
