package money.hejje.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM settings ({@code hejje.llm.*}, PRD 66C, docs/llm.md). Credentials are read from the environment variable named
 * by {@code api-key-env}; the key itself never appears in configuration, logs or the call log.
 *
 * @param enabled        master switch (false: every call fails with {@link LlmException.Unavailable}, callers degrade)
 * @param providers      name → provider config
 * @param profiles       workload profile ({@code fast, reasoning, news, research}) → provider + model
 * @param retries        attempts after the first on retryable failures (timeouts, 429, 5xx)
 * @param backoff        first retry delay (doubles per attempt, with ±50% jitter)
 * @param pricing        model → rupees per million input/output tokens for the cost estimate (optional)
 * @param dailyCostCap   rupees per IST day across all calls (estimated from {@code pricing}); reaching it disables the LLM
 *                       until the next day and records an {@code LLM_BUDGET_EXCEEDED} audit alert. Null: no cap
 * @param circuitBreaker per-provider breaker: consecutive retryable failures that open it, and for how long
 */
@ConfigurationProperties("hejje.llm")
public record LlmProperties(
        @DefaultValue("false") boolean enabled,
        Map<String, Provider> providers,
        Map<String, Profile> profiles,
        @DefaultValue("2") int retries,
        @DefaultValue("500ms") Duration backoff,
        Map<String, Pricing> pricing,
        BigDecimal dailyCostCap,
        @DefaultValue CircuitBreaker circuitBreaker) {

    public LlmProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
        circuitBreaker = circuitBreaker == null ? new CircuitBreaker(5, Duration.ofSeconds(60)) : circuitBreaker;
    }

    /**
     * @param type      {@code openai-compatible}, {@code gemini} or {@code fixture} (tests)
     * @param baseUrl   endpoint root; required for openai-compatible, defaults to Google's for gemini
     * @param apiKeyEnv env var holding the key (optional for local endpoints)
     */
    public record Provider(@DefaultValue("openai-compatible") String type, String baseUrl, String apiKeyEnv, String model, @DefaultValue("30s") Duration timeout) {

        /** Whether the env var named by {@code api-key-env} holds a value (the value itself is never exposed). */
        public boolean keyPresent() {
            if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
                return false;
            }
            String v = System.getenv(apiKeyEnv);
            return v != null && !v.isBlank();
        }

        /** Enough configuration to attempt a call: a base URL for openai-compatible, a key for gemini. */
        public boolean configured() {
            return switch (type) {
                case "openai-compatible" -> baseUrl != null && !baseUrl.isBlank();
                case "gemini" -> keyPresent();
                default -> true;
            };
        }
    }

    /** @param fallback optional profile tried once when this profile's provider fails or its circuit is open */
    public record Profile(String provider, String model, Double temperature, Integer maxTokens, String fallback) {
    }

    public record Pricing(double inputPerMillion, double outputPerMillion) {
    }

    public record CircuitBreaker(@DefaultValue("5") int failureThreshold, @DefaultValue("60s") Duration openFor) {
    }

    public Profile profile(String name) {
        Profile p = profiles.get(name);
        if (p == null) {
            throw new LlmException.Unavailable("No LLM profile '" + name + "' configured (hejje.llm.profiles)");
        }
        return p;
    }
}
