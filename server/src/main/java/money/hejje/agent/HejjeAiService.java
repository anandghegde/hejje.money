package money.hejje.agent;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import money.hejje.agent.internal.AnalystFlows;
import money.hejje.agent.internal.ConversationStore;
import money.hejje.agent.internal.GroundingChecker;
import money.hejje.common.Ids;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.time.HejjeClock;
import money.hejje.llm.LlmException;
import money.hejje.llm.LlmMessage;
import money.hejje.llm.LlmRequest;
import money.hejje.llm.LlmResponse;
import money.hejje.llm.LlmService;
import money.hejje.llm.LlmTool;
import money.hejje.llm.LlmToolCall;
import money.hejje.llm.Prompts;
import money.hejje.signals.SignalService;
import org.springframework.stereotype.Service;

/**
 * Hejje AI (PRD 56, 66D roles 1-3): answers a question by letting the LLM call the caller's permitted tools through
 * {@link AgentToolService} (at most {@code max-steps} LLM steps), with canned analyst flows that fetch evidence
 * deterministically first. The answer is grounding-checked against this turn's tool outputs. Every tool call is scoped
 * by the caller's credential and recorded in the conversation's agent session.
 */
@Service
public class HejjeAiService {

    public static final String PROMPT = "hejje_ai_v1.txt";
    public static final String PROMPT_VERSION = "hejje_ai_v1";

    public record Status(boolean enabled, boolean llmEnabled, String profile, String followUpProfile, int maxSteps, String reason) {}

    public record ConversationView(AiConversation conversation, List<AiMessage> messages) {}

    private final LlmService llm;
    private final AgentToolService tools;
    private final AnalystFlows flows;
    private final ConversationStore store;
    private final AgentProperties.Ai props;
    private final SignalService signals;
    private final HejjeClock clock;

    HejjeAiService(LlmService llm, AgentToolService tools, AnalystFlows flows, ConversationStore store, AgentProperties props, SignalService signals,
            HejjeClock clock) {
        this.llm = llm;
        this.tools = tools;
        this.flows = flows;
        this.store = store;
        this.props = props.ai();
        this.signals = signals;
        this.clock = clock;
    }

    public Status status() {
        String reason = !props.enabled() ? "Hejje AI is disabled (hejje.agent.ai.enabled=false)"
                : !llm.enabled() ? "The LLM is disabled (HEJJE_LLM_ENABLED=false); the trading platform works without it" : null;
        return new Status(reason == null, llm.enabled(), props.profile(), props.followUpProfile(), props.maxSteps(), reason);
    }

    public List<AiConversation> conversations(HejjePrincipal principal, int limit) {
        return store.conversations(principal.id(), Math.max(1, Math.min(limit, 100)));
    }

    public Optional<ConversationView> conversation(HejjePrincipal principal, UUID id) {
        return store.find(id).filter(c -> c.principalId().equals(principal.id())).map(c -> new ConversationView(c, store.messages(id)));
    }

    public AiTurn ask(HejjePrincipal principal, UUID conversationId, String question, String flow, Consumer<AiEvent> events) {
        Status status = status();
        if (!status.enabled()) {
            throw new LlmException.Unavailable(status.reason());
        }
        if (question == null || question.isBlank() || question.length() > 2_000) {
            throw new IllegalArgumentException("question is required (at most 2000 characters)");
        }
        AiConversation conversation = conversationId == null ? start(principal, question)
                : store.find(conversationId).filter(c -> c.principalId().equals(principal.id()))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown conversation " + conversationId));
        List<AiMessage> history = store.messages(conversation.id());
        String profile = history.isEmpty() ? props.profile() : props.followUpProfile();
        ToolContext ctx = new ToolContext(principal, conversation.sessionId(), null, "hejje-ai");

        List<String> outputs = new ArrayList<>();
        List<AiTraceStep> trace = new ArrayList<>();
        Consumer<ToolResult> onTool = r -> {
            outputs.add(toolContent(r));
            AiTraceStep step = new AiTraceStep(r.actionId(), r.tool(), r.status().name(), tools.descriptor(r.tool()).map(ToolDescriptor::requiredScope).orElse(null),
                    r.latencyMs(), r.error());
            trace.add(step);
            events.accept(new AiEvent.ToolCalled(step));
        };

        Optional<AnalystFlows.Detected> detected = flows.detect(question, flow);
        String userContent = detected.map(d -> question.strip() + "\n\n" + flows.run(d, ctx, onTool)).orElse(question.strip());

        List<LlmMessage> messages = new ArrayList<>();
        for (AiMessage m : history.subList(Math.max(0, history.size() - 2 * props.historyTurns()), history.size())) {
            messages.add("USER".equals(m.role()) ? LlmMessage.user(m.content()) : LlmMessage.assistant(m.content(), List.of()));
        }
        messages.add(LlmMessage.user(userContent));
        List<LlmTool> llmTools = tools.catalogFor(principal).stream().map(d -> new LlmTool(d.name(), d.description(), d.inputSchema())).toList();
        String system = Prompts.fill(Prompts.load(PROMPT),
                Map.of("now", clock.nowIst().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm")), "mode", signals.mode().name()));

        String answer = null;
        boolean limitReached = false;
        int steps = 0;
        while (answer == null) {
            if (steps == props.maxSteps()) {
                limitReached = true;
                answer = "I stopped after " + props.maxSteps() + " steps without a final answer. The tool calls made so far are in the trace.";
                break;
            }
            int step = ++steps;
            LlmResponse response = llm.stream(LlmRequest.chat(profile, "hejje-ai", PROMPT_VERSION, system, messages, llmTools),
                    delta -> events.accept(new AiEvent.Delta(step, delta)));
            if (!response.hasToolCalls()) {
                answer = response.text();
                break;
            }
            List<LlmToolCall> calls = new ArrayList<>();
            for (int i = 0; i < response.toolCalls().size(); i++) {
                LlmToolCall c = response.toolCalls().get(i);
                calls.add(new LlmToolCall(c.id() == null ? "call_" + step + "_" + i : c.id(), c.name(), c.arguments()));
            }
            messages.add(LlmMessage.assistant(response.text(), calls));
            for (LlmToolCall call : calls) {
                ToolResult result = tools.invoke(call.name(), call.arguments(), ctx);
                onTool.accept(result);
                messages.add(LlmMessage.tool(call.id(), call.name(), toolContent(result)));
            }
        }

        Grounding grounding = GroundingChecker.check(answer, outputs);
        String flowId = detected.map(d -> d.flow().id()).orElse(null);
        int seq = history.size();
        store.insertMessage(new AiMessage(Ids.newId(), conversation.id(), seq, "USER", question.strip(), flowId, null, null, List.of(), null, clock.now()));
        UUID answerId = Ids.newId();
        store.insertMessage(new AiMessage(answerId, conversation.id(), seq + 1, "ASSISTANT", answer, flowId, profile, grounding, trace, steps, clock.now()));
        store.touch(conversation.id(), clock.now());
        AiTurn turn = new AiTurn(conversation.id(), answerId, answer, grounding, trace, steps, profile, flowId, limitReached);
        events.accept(new AiEvent.Done(turn));
        return turn;
    }

    private AiConversation start(HejjePrincipal principal, String question) {
        AgentSession session = tools.startSession(principal, props.profile(), "chat");
        String title = question.strip().length() > 80 ? question.strip().substring(0, 80) + "…" : question.strip();
        AiConversation c = new AiConversation(Ids.newId(), session.id(), principal.id(), title, clock.now(), clock.now());
        store.insertConversation(c);
        return c;
    }

    /** What the model sees of a tool result: the output JSON (truncated) or the refusal/error. */
    private String toolContent(ToolResult r) {
        if (r.ok()) {
            String text = r.output().toString();
            return text.length() <= props.maxToolResultChars() ? text
                    : text.substring(0, props.maxToolResultChars()) + " …(truncated; ask a narrower question or use a filter)";
        }
        return "{\"status\":\"" + r.status() + "\",\"error\":" + quote(r.error()) + (r.details().isEmpty() ? "" : ",\"details\":" + quoteAll(r.details())) + "}";
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static String quoteAll(List<String> items) {
        return items.stream().map(HejjeAiService::quote).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
