package money.hejje.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.llm.JevResult.Outcome;
import money.hejje.llm.internal.JevStore;
import money.hejje.llm.internal.ProviderCircuits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Public API for Jev (plan M9.1, docs/jev.md): asks a {@link JevQuestionSet} about a state within a hard deadline and
 * records every call and answer. Never throws for a Jev problem: a caller reads {@link JevResult#ok()} and falls back to
 * its no-Jev behaviour. One retry, only for a retryable failure and only while at least half the deadline is left.
 */
@Service
public class JevService {

    private static final Logger log = LoggerFactory.getLogger(JevService.class);
    private static final String CIRCUIT = "jev";

    /** {@code GET /jev/status}. */
    public record Status(boolean enabled, boolean fixture, boolean keyPresent, String model, Duration timeout, String circuit, int consecutiveFailures,
            String lastError, JevStore.Usage today, BigDecimal dailyCostCapPaise, boolean budgetExceeded) {
    }

    private final JevProperties props;
    private final JevTransport transport;
    private final JevStore store;
    private final HejjeClock clock;
    private final HejjeProperties hejje;
    private final AuditService audit;
    private final ObjectMapper json;
    private final ProviderCircuits circuits;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<LocalDate> budgetAlerted = new AtomicReference<>();

    JevService(JevProperties props, JevTransport transport, JevStore store, HejjeClock clock, HejjeProperties hejje, AuditService audit, ObjectMapper json) {
        this.props = props;
        this.transport = transport;
        this.store = store;
        this.clock = clock;
        this.hejje = hejje;
        this.audit = audit;
        this.json = json;
        this.circuits = new ProviderCircuits(props.circuitBreaker().failureThreshold(), props.circuitBreaker().openFor());
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** The default deadline ({@code hejje.jev.timeout}). */
    public Duration timeout() {
        return props.timeout();
    }

    /** {@link #evaluate(String, String, JsonNode, JevQuestionSet, Duration)} with {@code hejje.jev.timeout}. */
    public JevResult evaluate(String purpose, String subject, JsonNode state, JevQuestionSet set) {
        return evaluate(purpose, subject, state, set, props.timeout());
    }

    /**
     * @param purpose  what the answer is for ({@code news}, {@code bot-stage1}, ...); calibration groups by it
     * @param subject  what it is about (a symbol, a signal id), or null
     * @param deadline the whole call, retry included, finishes within it
     * @throws IllegalArgumentException when the set has more than {@code max-questions-per-call} questions (a programming error)
     */
    public JevResult evaluate(String purpose, String subject, JsonNode state, JevQuestionSet set, Duration deadline) {
        if (!props.enabled()) {
            return JevResult.failed(Outcome.DISABLED, 0, null, "Jev disabled (hejje.jev.enabled=false)");
        }
        if (set.questions().size() > props.maxQuestionsPerCall()) {
            throw new IllegalArgumentException("Question set " + set.name() + " has " + set.questions().size() + " questions; the limit is "
                    + props.maxQuestionsPerCall());
        }
        long started = System.nanoTime();
        JsonNode clean = sanitize(state);
        String hash = hash(clean);
        Call call = new Call(purpose, subject, set, hash, clean);
        try {
            if (hejje.mode() == ExecutionMode.SIM && props.simCache()) {
                var cached = store.findCached(hash, set.name(), set.version());
                if (cached.isPresent()) {
                    return call.record(Outcome.CACHED, elapsedMs(started), cached.get().model(), cached.get().inputTokens(), BigDecimal.ZERO,
                            cached.get().answers(), null);
                }
            }
            if (budgetExceeded()) {
                return call.record(Outcome.BUDGET, elapsedMs(started), null, null, null, Map.of(), "Jev daily cost cap reached");
            }
            ObjectNode body = json.createObjectNode();
            body.put("model", props.model());
            body.set("state", clean);
            ObjectNode questions = body.putObject("questions");
            set.questions().forEach((k, q) -> questions.set(k, q.toJson()));
            return send(call, body, deadline, started);
        } catch (RuntimeException e) {
            log.warn("Jev call for {} failed unexpectedly: {}", purpose, e.toString());
            return JevResult.failed(Outcome.FAILED, elapsedMs(started), null, e.getMessage());
        }
    }

    private JevResult send(Call call, ObjectNode body, Duration deadline, long started) {
        long deadlineMs = deadline.toMillis();
        for (int attempt = 0; ; attempt++) {
            long remaining = deadlineMs - elapsedMs(started);
            if (remaining <= 0) {
                return call.record(Outcome.TIMEOUT, elapsedMs(started), null, null, null, Map.of(), "No answer within " + deadlineMs + " ms");
            }
            if (!circuits.allow(CIRCUIT, clock.now())) {
                return call.record(Outcome.CIRCUIT_OPEN, elapsedMs(started), null, null, null, Map.of(), "Jev is failing (circuit open)");
            }
            // The transport's own timeout is looser than the deadline so the deadline, not the HTTP client, decides TIMEOUT.
            Future<JsonNode> future = calls.submit(() -> transport.send(body, Duration.ofMillis(remaining + 500)));
            try {
                JsonNode root = future.get(remaining, TimeUnit.MILLISECONDS);
                circuits.success(CIRCUIT, clock.now());
                Map<String, JevAnswer> answers = new LinkedHashMap<>();
                root.path("answers").fields().forEachRemaining(e -> answers.put(e.getKey(), JevAnswer.fromJson(e.getKey(), e.getValue())));
                JsonNode tokens = root.path("usage").path("input_tokens");
                Integer inputTokens = tokens.isNumber() ? tokens.asInt() : null;
                return call.record(Outcome.OK, elapsedMs(started), answeredModel(root.path("model").asText(null)), inputTokens, costPaise(inputTokens), answers,
                        null);
            } catch (TimeoutException e) {
                future.cancel(true);
                failure("No answer within " + deadlineMs + " ms", true);
                return call.record(Outcome.TIMEOUT, elapsedMs(started), null, null, null, Map.of(), "No answer within " + deadlineMs + " ms");
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return call.record(Outcome.FAILED, elapsedMs(started), null, null, null, Map.of(), "Interrupted");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                boolean retryable = cause instanceof LlmException le && le.isRetryable();
                String message = cause == null ? e.toString() : cause.getMessage();
                failure(message, retryable);
                long left = deadlineMs - elapsedMs(started);
                if (retryable && attempt == 0 && left * 2 >= deadlineMs) {
                    continue;
                }
                return call.record(Outcome.FAILED, elapsedMs(started), null, null, null, Map.of(), message);
            }
        }
    }

    private void failure(String message, boolean retryable) {
        Instant now = clock.now();
        boolean wasOpen = circuits.health(CIRCUIT, now).state() == ProviderCircuits.State.OPEN;
        circuits.failure(CIRCUIT, now, message, retryable);
        if (!wasOpen && circuits.health(CIRCUIT, now).state() == ProviderCircuits.State.OPEN) {
            log.warn("Jev circuit opened after repeated failures: {}", message);
            audit.record(AuditEvent.of(AuditEventType.JEV_CIRCUIT_OPEN, ActorType.SYSTEM).withActorId("jev")
                    .withPayload(Map.of("error", message == null ? "" : message, "openFor", props.circuitBreaker().openFor().toString())));
        }
    }

    private boolean budgetExceeded() {
        BigDecimal cap = capPaise();
        if (cap == null) {
            return false;
        }
        BigDecimal spent = store.costSince(dayStart());
        if (spent.compareTo(cap) < 0) {
            return false;
        }
        LocalDate today = clock.today();
        if (!today.equals(budgetAlerted.getAndSet(today))) {
            log.warn("Jev daily cost cap reached: spent {} paise of {} paise; Jev calls stop until tomorrow", spent, cap);
            audit.record(AuditEvent.of(AuditEventType.JEV_BUDGET_EXCEEDED, ActorType.SYSTEM).withActorId("jev")
                    .withPayload(Map.of("date", today.toString(), "spentPaise", spent, "capPaise", cap)));
        }
        return true;
    }

    /** Estimated cost from {@code input-per-million} (rupees); output tokens are free. Null when unknown. */
    BigDecimal costPaise(Integer inputTokens) {
        if (inputTokens == null || props.inputPerMillion() == null) {
            return null;
        }
        return props.inputPerMillion().multiply(BigDecimal.valueOf(inputTokens)).movePointRight(2).divide(BigDecimal.valueOf(1_000_000), 4,
                RoundingMode.HALF_UP);
    }

    private BigDecimal capPaise() {
        return props.dailyCostCap() == null ? null : props.dailyCostCap().movePointRight(2);
    }

    private Instant dayStart() {
        return clock.today().atStartOfDay(clock.zone()).toInstant();
    }

    /**
     * The model version an answer is recorded under. The API echoes an alias ({@code jev-latest}) even for a pinned
     * request, and calibration is grouped by version, so an alias or a missing echo records the pinned model instead.
     */
    String answeredModel(String echoed) {
        return echoed == null || echoed.isBlank() || echoed.endsWith("-latest") ? props.model() : echoed;
    }

    public Status status() {
        Instant now = clock.now();
        ProviderCircuits.Health h = circuits.health(CIRCUIT, now);
        JevStore.Usage today = store.usageSince(dayStart());
        BigDecimal cap = capPaise();
        return new Status(props.enabled(), props.fixture(), props.keyPresent(), props.model(), props.timeout(), h.state().name(), h.consecutiveFailures(),
                h.lastError(), today, cap, cap != null && today.costPaise().compareTo(cap) >= 0);
    }

    public List<JevCall> recentCalls(String purpose, String subject, int limit) {
        return store.recent(purpose, subject, Math.max(1, Math.min(limit, 500)));
    }

    @PreDestroy
    void close() {
        calls.shutdownNow();
    }

    /** One evaluation's identity; {@link #record} writes the call and turns it into a result. */
    private final class Call {
        final String purpose;
        final String subject;
        final JevQuestionSet set;
        final String hash;
        final JsonNode state;

        Call(String purpose, String subject, JevQuestionSet set, String hash, JsonNode state) {
            this.purpose = purpose;
            this.subject = subject;
            this.set = set;
            this.hash = hash;
            this.state = state;
        }

        JevResult record(Outcome outcome, long latencyMs, String model, Integer inputTokens, BigDecimal cost, Map<String, JevAnswer> answers, String error) {
            UUID id = Ids.newId();
            try {
                store.insert(new JevCall(id, clock.now(), purpose, subject, set.name(), set.version(), model, hash, latencyMs, inputTokens, cost,
                        outcome.name(), error, List.copyOf(answers.values())), state);
            } catch (RuntimeException e) {
                log.warn("Could not record Jev call: {}", e.getMessage());
                id = null;
            }
            boolean ok = outcome == Outcome.OK || outcome == Outcome.CACHED;
            return ok ? new JevResult(true, outcome, answers, latencyMs, inputTokens, model, id, null) : JevResult.failed(outcome, latencyMs, id, error);
        }
    }

    /** Non-finite numbers become null anywhere in the tree (the API rejects NaN and Infinity). */
    static JsonNode sanitize(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), sanitize(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(n -> out.add(sanitize(n)));
            return out;
        }
        if (node.isFloatingPointNumber() && !Double.isFinite(node.asDouble())) {
            return JsonNodeFactory.instance.nullNode();
        }
        return node;
    }

    private String hash(JsonNode state) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] digest = d.digest(json.writeValueAsString(state).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
