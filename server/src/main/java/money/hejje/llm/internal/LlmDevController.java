package money.hejje.llm.internal;

import java.util.Map;
import money.hejje.llm.FixtureLlmProvider;
import money.hejje.llm.LlmService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev/test only ({@code hejje.llm.dev-fixture-endpoint=true}): registers canned answers on a {@code type: fixture}
 * provider so the web e2e can exercise Hejje AI without a real model.
 */
@RestController
@RequestMapping("/api/v1/agents/llm/dev")
@ConditionalOnProperty(name = "hejje.llm.dev-fixture-endpoint", havingValue = "true")
@PreAuthorize("hasAuthority('SCOPE_admin')")
class LlmDevController {

    record FixtureAnswer(String provider, String contains, String response) {}

    private final LlmService llm;

    LlmDevController(LlmService llm) {
        this.llm = llm;
    }

    @PostMapping("/fixture")
    ResponseEntity<Map<String, Object>> register(@RequestBody FixtureAnswer answer) {
        String name = answer.provider() == null ? "fixture" : answer.provider();
        if (answer.contains() == null || answer.response() == null) {
            throw new IllegalArgumentException("contains and response are required");
        }
        return llm.provider(name).filter(FixtureLlmProvider.class::isInstance).map(p -> {
            ((FixtureLlmProvider) p).respondWhenContains(answer.contains(), answer.response());
            return ResponseEntity.ok(Map.<String, Object>of("provider", name, "registered", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
