package money.hejje.llm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.common.CorrelationContext;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import money.hejje.llm.internal.LlmCallStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Public API of the llm module: routes a request through its profile's provider with retries and backoff, and logs
 * every attempt's outcome to {@code llm_call}. Throws {@link LlmException.Unavailable} when disabled; callers must
 * degrade (README rule 7).
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final LlmProperties props;
    private final Map<String, LlmProvider> providers;
    private final LlmCallStore calls;
    private final HejjeClock clock;

    LlmService(LlmProperties props, List<LlmProvider> providerBeans, LlmCallStore calls, HejjeClock clock) {
        this.props = props;
        this.providers = providerBeans.stream().collect(java.util.stream.Collectors.toMap(LlmProvider::name, p -> p, (a, b) -> a));
        this.calls = calls;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    public Optional<LlmProvider> provider(String name) {
        return Optional.ofNullable(providers.get(name));
    }

    public LlmResponse complete(LlmRequest request) {
        if (!props.enabled()) {
            throw new LlmException.Unavailable("LLM disabled (hejje.llm.enabled=false)");
        }
        LlmProperties.Profile profile = props.profile(request.profile());
        LlmProvider provider = providers.get(profile.provider());
        if (provider == null) {
            throw new LlmException.Unavailable("Profile '" + request.profile() + "' routes to unknown provider '" + profile.provider() + "'");
        }
        LlmRequest effective = new LlmRequest(request.profile(), request.purpose(), request.promptVersion(), request.systemPrompt(), request.userPrompt(),
                request.maxTokens() != null ? request.maxTokens() : profile.maxTokens(), request.temperature() != null ? request.temperature() : profile.temperature(),
                request.jsonMode());
        String hash = Prompts.hash(effective.systemPrompt(), effective.userPrompt());
        long backoff = props.backoff().toMillis();
        LlmException last = null;
        for (int attempt = 0; attempt <= props.retries(); attempt++) {
            long started = System.nanoTime();
            try {
                LlmResponse response = provider.complete(effective, profile.model());
                record(effective, provider.name(), response.model(), hash, response, (System.nanoTime() - started) / 1_000_000, "OK", null);
                return response;
            } catch (LlmException e) {
                last = e;
                record(effective, provider.name(), profile.model(), hash, null, (System.nanoTime() - started) / 1_000_000, e.isRetryable() ? "RETRY" : "FAILED", e.getMessage());
                if (!e.isRetryable() || attempt == props.retries()) {
                    break;
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff *= 2;
            }
        }
        throw last;
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

    public List<LlmCall> recentCalls(int limit) {
        return calls.recent(limit);
    }

    public Instant now() {
        return clock.now();
    }
}
