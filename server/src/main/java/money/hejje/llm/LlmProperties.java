package money.hejje.llm;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM settings ({@code hejje.llm.*}, PRD 66C, docs/llm.md). Credentials are read from the environment variable named
 * by {@code api-key-env}; the key itself never appears in configuration, logs or the call log.
 *
 * @param enabled   master switch (false: every call fails with {@link LlmException.Unavailable}, callers degrade)
 * @param providers name → provider config
 * @param profiles  workload profile ({@code fast, reasoning, news, research}) → provider + model
 * @param retries   attempts after the first on retryable failures (timeouts, 429, 5xx)
 * @param backoff   first retry delay (doubles per attempt)
 * @param pricing   model → rupees per million input/output tokens for the cost estimate (optional)
 */
@ConfigurationProperties("hejje.llm")
public record LlmProperties(
        @DefaultValue("false") boolean enabled,
        Map<String, Provider> providers,
        Map<String, Profile> profiles,
        @DefaultValue("2") int retries,
        @DefaultValue("500ms") Duration backoff,
        Map<String, Pricing> pricing) {

    public LlmProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
    }

    /** @param type {@code openai-compatible} or {@code fixture} (tests); @param apiKeyEnv env var holding the key (optional for local endpoints) */
    public record Provider(@DefaultValue("openai-compatible") String type, String baseUrl, String apiKeyEnv, String model, @DefaultValue("30s") Duration timeout) {
    }

    public record Profile(String provider, String model, Double temperature, Integer maxTokens) {
    }

    public record Pricing(double inputPerMillion, double outputPerMillion) {
    }

    public Profile profile(String name) {
        Profile p = profiles.get(name);
        if (p == null) {
            throw new LlmException.Unavailable("No LLM profile '" + name + "' configured (hejje.llm.profiles)");
        }
        return p;
    }
}
