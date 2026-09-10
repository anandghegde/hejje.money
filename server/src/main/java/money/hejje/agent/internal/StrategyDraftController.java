package money.hejje.agent.internal;

import money.hejje.agent.StrategyBuilder;
import money.hejje.agent.StrategyDraft;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.llm.LlmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** {@code POST /api/v1/strategies/drafts}: the Lab's "Describe a strategy" panel (plan M4.6). 201 with the draft, 422 when no valid definition came out. */
@RestController
class StrategyDraftController {

    record DraftRequest(String description, String strategy) {}

    private final StrategyBuilder builder;

    StrategyDraftController(StrategyBuilder builder) {
        this.builder = builder;
    }

    @PostMapping("/api/v1/strategies/drafts")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    ResponseEntity<StrategyDraft> draft(@RequestBody DraftRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        try {
            StrategyDraft draft = builder.draft(body.description(), body.strategy(), principal);
            return ResponseEntity.status(draft.created() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(draft);
        } catch (LlmException.Unavailable e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (LlmException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The LLM failed: " + e.getMessage());
        }
    }
}
