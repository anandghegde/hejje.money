package money.hejje.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.agent.AiConversation;
import money.hejje.agent.AiEvent;
import money.hejje.agent.AiTurn;
import money.hejje.agent.HejjeAiService;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.llm.LlmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hejje AI chat. {@code POST /ask} answers as JSON, or as server-sent events when the client accepts
 * {@code text/event-stream} ({@code tool}, {@code delta}, {@code done}, {@code error}). The stream is written on the request
 * thread, so the caller's security context and correlation id cover every tool call.
 */
@RestController
@RequestMapping("/api/v1/agents/ai")
@PreAuthorize("isAuthenticated()")
class HejjeAiController {

    record AskRequest(String question, UUID conversationId, String flow) {}

    private final HejjeAiService ai;
    private final ObjectMapper json;

    HejjeAiController(HejjeAiService ai, ObjectMapper json) {
        this.ai = ai;
        this.json = json;
    }

    @GetMapping("/status")
    HejjeAiService.Status status() {
        return ai.status();
    }

    @GetMapping("/conversations")
    List<AiConversation> conversations(@RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal HejjePrincipal principal) {
        return ai.conversations(principal, limit);
    }

    @GetMapping("/conversations/{id}")
    ResponseEntity<HejjeAiService.ConversationView> conversation(@PathVariable UUID id, @AuthenticationPrincipal HejjePrincipal principal) {
        return ai.conversation(principal, id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(path = "/ask", produces = MediaType.APPLICATION_JSON_VALUE)
    AiTurn ask(@RequestBody AskRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        try {
            return ai.ask(principal, body.conversationId(), body.question(), body.flow(), e -> { });
        } catch (LlmException.Unavailable e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (LlmException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The LLM failed: " + e.getMessage());
        }
    }

    @PostMapping(path = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    void askStream(@RequestBody AskRequest body, @AuthenticationPrincipal HejjePrincipal principal, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        PrintWriter out = response.getWriter();
        try {
            ai.ask(principal, body.conversationId(), body.question(), body.flow(), event -> send(out, event));
        } catch (LlmException | IllegalArgumentException e) {
            sse(out, "error", Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void send(PrintWriter out, AiEvent event) {
        switch (event) {
            case AiEvent.ToolCalled t -> sse(out, "tool", t.step());
            case AiEvent.Delta d -> sse(out, "delta", d);
            case AiEvent.Done d -> sse(out, "done", d.turn());
        }
    }

    private void sse(PrintWriter out, String name, Object data) {
        try {
            out.write("event: " + name + "\ndata: " + json.writeValueAsString(data) + "\n\n");
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
