package money.hejje.llm.internal;

import money.hejje.llm.LlmException;
import money.hejje.llm.LlmRequest;
import money.hejje.llm.LlmResponse;
import money.hejje.llm.LlmService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** LLM provider status and a trivial connectivity test (plan M4.1). */
@RestController
@RequestMapping("/api/v1/agents/llm")
class LlmController {

    record TestRequest(String profile) {}

    record TestResult(boolean ok, String profile, String provider, String model, String text, Integer inputTokens, Integer outputTokens,
            Long latencyMs, String error) {}

    private final LlmService llm;

    LlmController(LlmService llm) {
        this.llm = llm;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    LlmService.Status status() {
        return llm.status();
    }

    @PostMapping("/test")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    TestResult test(@RequestBody(required = false) TestRequest body) {
        String profile = body == null || body.profile() == null || body.profile().isBlank() ? "fast" : body.profile();
        try {
            LlmResponse r = llm.complete(new LlmRequest(profile, "llm-test", "v1", null, "Reply with the single word OK.", 5, 0.0, false));
            return new TestResult(true, profile, r.provider(), r.model(), r.text(), r.inputTokens(), r.outputTokens(), r.latencyMs(), null);
        } catch (LlmException e) {
            return new TestResult(false, profile, null, null, null, null, null, null, e.getMessage());
        }
    }
}
