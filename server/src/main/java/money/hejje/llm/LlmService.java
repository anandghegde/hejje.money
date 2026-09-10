package money.hejje.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.CorrelationContext;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import money.hejje.llm.internal.LlmCallStore;
import money.hejje.llm.internal.ProviderCircuits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Public API of the llm module: routes a request through its profile's provider with retries (doubling backoff with
 * jitter), a per-provider circuit breaker and an optional fallback profile, enforces the daily cost cap, and logs every
 * attempt's outcome to {@code llm_call}. Throws {@link LlmException.Unavailable} when disabled; callers must degrade
 * (README rule 7).
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final long STREAM_LIMIT_MINUTES = 5;

    public record ProviderStatus(String name, String type, String model, boolean configured, boolean keyPresent, String circuit, int consecutiveFailures,
            Instant lastSuccessAt, Instant lastFailureAt, String lastError) {}

    /** {@code GET /agents/llm/status}: providers with their circuit, profiles, today's usage against the cap. */
    public record Status(boolean enabled, List<ProviderStatus> providers, Map<String, LlmProperties.Profile> profiles, LlmUsage today,
            Long dailyCostCapPaise, boolean budgetExceeded) {}

    private final LlmProperties props;
    private final Map<String, LlmProvider> providers;
    private final LlmCallStore calls;
    private final HejjeClock clock;
    private final AuditService audit;
    private final ProviderCircuits circuits;
    private final AtomicReference<LocalDate> budgetAlerted = new AtomicReference<>();

    LlmService(LlmProperties props, List<LlmProvider> providerBeans, LlmCallStore calls, HejjeClock clock, AuditService audit) {
        this.props = props;
        this.providers = providerBeans.stream().collect(java.util.stream.Collectors.toMap(LlmProvider::name, p -> p, (a, b) -> a));
        this.calls = calls;
        this.clock = clock;
        this.audit = audit;
        this.circuits = new ProviderCircuits(props.circuitBreaker().failureThreshold(), props.circuitBreaker().openFor());
    }

    public boolean enabled() {
        return props.enabled();
    }

    public Optional<LlmProvider> provider(String name) {
        return Optional.ofNullable(providers.get(name));
    }

    public LlmResponse complete(LlmRequest request) {
        return execute(request, null);
    }

    /**
     * Streams the completion, passing text deltas to {@code onDelta} as they arrive, and returns the full response (text,
     * tool calls, usage). A failure after the first delta is neither retried nor sent to the fallback profile.
     */
    public LlmResponse stream(LlmRequest request, Consumer<String> onDelta) {
        return execute(request, new DeltaSink(onDelta));
    }

    private LlmResponse execute(LlmRequest request, DeltaSink sink) {
        if (!props.enabled()) {
            throw new LlmException.Unavailable("LLM disabled (hejje.llm.enabled=false)");
        }
        checkBudget();
        LlmProperties.Profile profile = props.profile(request.profile());
        try {
            return run(request, profile, sink);
        } catch (LlmException e) {
            String fallback = profile.fallback();
            if (fallback == null || fallback.isBlank() || fallback.equals(request.profile()) || (sink != null && sink.emitted)) {
                throw e;
            }
            log.warn("LLM profile '{}' failed ({}); trying fallback profile '{}'", request.profile(), e.getMessage(), fallback);
            return run(request.withProfile(fallback), props.profile(fallback), sink);
        }
    }

    private LlmResponse run(LlmRequest request, LlmProperties.Profile profile, DeltaSink sink) {
        LlmProvider provider = providers.get(profile.provider());
        if (provider == null) {
            throw new LlmException.Unavailable("Profile '" + request.profile() + "' routes to unknown provider '" + profile.provider() + "'");
        }
        Double temperature = profile.temperature() != null ? profile.temperature() : request.jsonMode() ? Double.valueOf(0.0) : null;
        LlmRequest effective = request.withDefaults(profile.maxTokens(), temperature);
        String hash = Prompts.hash(effective.systemPrompt(), effective.promptText());
        long backoff = props.backoff().toMillis();
        for (int attempt = 0; ; attempt++) {
            if (!circuits.allow(provider.name(), clock.now())) {
                throw new LlmException.Unavailable("Provider '" + provider.name() + "' is failing (circuit open)");
            }
            long started = System.nanoTime();
            try {
                LlmResponse response = sink == null ? provider.complete(effective, profile.model())
                        : collect(provider.stream(effective, profile.model()), sink, provider.name(), profile.model());
                circuits.success(provider.name(), clock.now());
                record(effective, provider.name(), response.model(), hash, response, elapsedMs(started), "OK", null);
                return response;
            } catch (LlmException e) {
                circuits.failure(provider.name(), clock.now(), e.getMessage(), e.isRetryable());
                boolean retry = e.isRetryable() && attempt < props.retries() && (sink == null || !sink.emitted);
                record(effective, provider.name(), profile.model(), hash, null, elapsedMs(started), retry ? "RETRY" : "FAILED", e.getMessage());
                if (!retry) {
                    throw e;
                }
                sleep(jittered(backoff));
                backoff *= 2;
            }
        }
    }

    /** Blocks on a provider stream, forwarding deltas; the last chunk's response wins, else the deltas are assembled. */
    private LlmResponse collect(Flow.Publisher<LlmChunk> publisher, DeltaSink sink, String provider, String model) {
        CompletableFuture<LlmResponse> result = new CompletableFuture<>();
        StringBuilder text = new StringBuilder();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(LlmChunk chunk) {
                if (chunk.delta() != null && !chunk.delta().isEmpty()) {
                    text.append(chunk.delta());
                    sink.accept(chunk.delta());
                }
                if (chunk.last()) {
                    result.complete(chunk.response() != null ? chunk.response() : new LlmResponse(text.toString(), provider, model, null, null, 0));
                }
            }

            @Override
            public void onError(Throwable error) {
                result.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                result.completeExceptionally(new LlmException("Stream from " + provider + " ended without a final chunk", true));
            }
        });
        try {
            return result.get(STREAM_LIMIT_MINUTES, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LlmException le) {
                throw le;
            }
            throw new LlmException("Stream from " + provider + " failed: " + e.getCause(), false, e.getCause());
        } catch (TimeoutException e) {
            throw new LlmException("Stream from " + provider + " timed out", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted streaming from " + provider, false, e);
        }
    }

    private void checkBudget() {
        Long cap = capPaise();
        if (cap == null) {
            return;
        }
        long spent = calls.costSince(dayStart());
        if (spent < cap) {
            return;
        }
        LocalDate today = clock.today();
        if (!today.equals(budgetAlerted.getAndSet(today))) {
            log.warn("LLM daily cost cap reached: spent {} paise of {} paise; LLM calls disabled until tomorrow", spent, cap);
            audit.record(AuditEvent.of(AuditEventType.LLM_BUDGET_EXCEEDED, ActorType.SYSTEM).withActorId("llm")
                    .withPayload(Map.of("date", today.toString(), "spentPaise", spent, "capPaise", cap)));
        }
        throw new LlmException.BudgetExceeded("LLM daily cost cap reached (" + spent + " of " + cap + " paise)");
    }

    private Long capPaise() {
        BigDecimal cap = props.dailyCostCap();
        return cap == null ? null : cap.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private Instant dayStart() {
        return clock.today().atStartOfDay(clock.zone()).toInstant();
    }

    public LlmUsage usageToday() {
        return calls.usageSince(dayStart());
    }

    public Status status() {
        Instant now = clock.now();
        List<ProviderStatus> list = new ArrayList<>();
        for (Map.Entry<String, LlmProperties.Provider> e : new TreeMap<>(props.providers()).entrySet()) {
            ProviderCircuits.Health h = circuits.health(e.getKey(), now);
            LlmProperties.Provider p = e.getValue();
            list.add(new ProviderStatus(e.getKey(), p.type(), p.model(), p.configured(), p.keyPresent(), h.state().name(), h.consecutiveFailures(),
                    h.lastSuccessAt(), h.lastFailureAt(), h.lastError()));
        }
        LlmUsage today = usageToday();
        Long cap = capPaise();
        return new Status(props.enabled(), list, new TreeMap<>(props.profiles()), today, cap, cap != null && today.costPaise() >= cap);
    }

    private void record(LlmRequest r, String provider, String model, String hash, LlmResponse response, long latency, String status, String error) {
        try {
            Long cost = response == null ? null : estimateCostPaise(model, response.inputTokens(), response.outputTokens());
            calls.insert(new LlmCall(Ids.newId(), clock.now(), r.profile(), provider, model, r.purpose(), r.promptVersion(), hash,
                    response == null ? null : response.inputTokens(), response == null ? null : response.outputTokens(), cost, latency,
                    CorrelationContext.get().map(Object::toString).orElse(null), status, error));
        } catch (RuntimeException e) {
            log.warn("Could not log LLM call: {}", e.getMessage());
        }
    }

    /** Cost in paise from {@code hejje.llm.pricing} (rupees per million tokens), or null when unknown. */
    Long estimateCostPaise(String model, Integer in, Integer out) {
        LlmProperties.Pricing p = model == null ? null : props.pricing().get(model);
        if (p == null || in == null || out == null) {
            return null;
        }
        double rupees = in * p.inputPerMillion() / 1_000_000 + out * p.outputPerMillion() / 1_000_000;
        return Math.round(rupees * 100);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static long jittered(long backoffMs) {
        return backoffMs <= 0 ? 0 : (long) (backoffMs * (0.5 + ThreadLocalRandom.current().nextDouble()));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted during LLM retry backoff", false, e);
        }
    }

    public List<LlmCall> recentCalls(int limit) {
        return calls.recent(limit);
    }

    public Instant now() {
        return clock.now();
    }

    private static final class DeltaSink {
        private final Consumer<String> target;
        private volatile boolean emitted;

        DeltaSink(Consumer<String> target) {
            this.target = target;
        }

        void accept(String delta) {
            emitted = true;
            target.accept(delta);
        }
    }
}
