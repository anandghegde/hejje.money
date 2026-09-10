package money.hejje.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import money.hejje.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class LlmStatusIT extends AbstractIntegrationTest {

    @Autowired
    LlmService llm;

    @Test
    @SuppressWarnings("unchecked")
    void statusShowsProvidersProfilesAndTodaysUsageAndTheTestCallIsAdminOnly() {
        FixtureLlmProvider fx = (FixtureLlmProvider) llm.provider("fixture").orElseThrow();
        fx.respondWhenContains("Reply with the single word OK.", "OK");
        String admin = adminAccessToken();

        ResponseEntity<Map> test = rest.exchange("/api/v1/agents/llm/test", HttpMethod.POST, new HttpEntity<>(Map.of("profile", "fast"), bearer(admin)), Map.class);
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(test.getBody()).containsEntry("ok", true).containsEntry("text", "OK").containsEntry("provider", "fixture");

        ResponseEntity<Map> unknown = rest.exchange("/api/v1/agents/llm/test", HttpMethod.POST, new HttpEntity<>(Map.of("profile", "nope"), bearer(admin)), Map.class);
        assertThat(unknown.getBody()).containsEntry("ok", false);
        assertThat((String) unknown.getBody().get("error")).contains("No LLM profile 'nope'");

        ResponseEntity<Map> status = rest.exchange("/api/v1/agents/llm/status", HttpMethod.GET, new HttpEntity<>(bearer(admin)), Map.class);
        assertThat(status.getBody()).containsEntry("enabled", true).containsEntry("budgetExceeded", false);
        List<Map<String, Object>> providers = (List<Map<String, Object>>) status.getBody().get("providers");
        assertThat(providers).anySatisfy(p -> assertThat(p).containsEntry("name", "fixture").containsEntry("type", "fixture").containsEntry("circuit", "CLOSED"));
        assertThat((Map<String, Object>) status.getBody().get("profiles")).containsKey("fast");
        assertThat(((Number) ((Map<String, Object>) status.getBody().get("today")).get("calls")).longValue()).isGreaterThanOrEqualTo(1);

        ResponseEntity<Map> created = rest.postForEntity("/api/v1/auth/clients",
                new HttpEntity<>(Map.of("name", "llm-status-research", "scopes", List.of("market:read", "strategies:read")), bearer(admin)), Map.class);
        String key = (String) created.getBody().get("key");
        assertThat(rest.exchange("/api/v1/agents/llm/status", HttpMethod.GET, new HttpEntity<>(bearer(key)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/v1/agents/llm/test", HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(key)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
