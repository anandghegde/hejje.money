package money.hejje.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import money.hejje.llm.JevCall;
import money.hejje.llm.JevQuestionSet;
import money.hejje.llm.JevQuestionSets;
import money.hejje.llm.JevResult;
import money.hejje.llm.JevService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Jev status, recorded calls, and a manual evaluation for trying a question set out (plan M9.1). */
@RestController
@RequestMapping("/api/v1/jev")
class JevController {

    /** Either {@code set} (a name under config/jev) or inline {@code questions} in the API's shape. */
    record EvaluateRequest(JsonNode state, String set, JsonNode questions) {}

    private final JevService jev;
    private final JevQuestionSets sets;

    JevController(JevService jev, JevQuestionSets sets) {
        this.jev = jev;
        this.sets = sets;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    JevService.Status status() {
        return jev.status();
    }

    @GetMapping("/calls")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<JevCall> calls(@RequestParam(required = false) String purpose, @RequestParam(required = false) String subject,
            @RequestParam(defaultValue = "50") int limit) {
        return jev.recentCalls(purpose, subject, limit);
    }

    @PostMapping("/evaluate")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    JevResult evaluate(@RequestBody EvaluateRequest body) {
        if (body == null || body.state() == null) {
            throw new IllegalArgumentException("state is required");
        }
        JevQuestionSet set;
        if (body.set() != null && !body.set().isBlank()) {
            set = sets.get(body.set());
        } else if (body.questions() != null && body.questions().isObject()) {
            var root = body.questions().deepCopy();
            set = JevQuestionSet.fromJson(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("name", "manual").put("version", "inline").set("questions", root));
        } else {
            throw new IllegalArgumentException("Give a question set name (set) or inline questions");
        }
        return jev.evaluate("manual", null, body.state(), set);
    }
}
