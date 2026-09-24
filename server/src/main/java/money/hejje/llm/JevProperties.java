package money.hejje.llm;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Jev settings ({@code hejje.jev.*}, plan M9.1, docs/jev.md). Independent of {@code hejje.llm.enabled}: Jev is a typed
 * decision model, not a chat provider. The key is read from the environment variable named by {@code api-key-env}.
 *
 * @param enabled             master switch (false: every call returns {@code DISABLED} and nothing is recorded)
 * @param baseUrl             API root ({@code https://api.surplusintelligence.ai}); {@code fixture} selects {@link FixtureJev}
 * @param model               model id sent with every call; pinned to a version because calibration is per version
 * @param timeout             default deadline of one evaluation, retry included
 * @param maxQuestionsPerCall a larger question set is a programming error
 * @param inputPerMillion     rupees per million input tokens for the cost estimate (output tokens are free); null: no estimate
 * @param dailyCostCap        rupees per IST day; reaching it stops calls until the next day. Null: no cap
 * @param circuitBreaker      consecutive retryable failures that open the circuit, and for how long
 * @param simCache            in SIM, a state already answered with the same question set version is answered from the store
 * @param stateRetentionDays  days the sent state is kept ({@code jev_state}); answers are kept
 * @param knowledgeCutoff     the release date of {@code model}: a JEV bot's knowledge cutoff (plan M9.5); no default
 */
@ConfigurationProperties("hejje.jev")
public record JevProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://api.surplusintelligence.ai") String baseUrl,
        @DefaultValue("HEJJE_JEV_API_KEY") String apiKeyEnv,
        @DefaultValue("jev-1.13") String model,
        @DefaultValue("2500ms") Duration timeout,
        @DefaultValue("120") int maxQuestionsPerCall,
        BigDecimal inputPerMillion,
        BigDecimal dailyCostCap,
        @DefaultValue LlmProperties.CircuitBreaker circuitBreaker,
        @DefaultValue("true") boolean simCache,
        @DefaultValue("30") int stateRetentionDays,
        java.time.LocalDate knowledgeCutoff) {

    public JevProperties {
        circuitBreaker = circuitBreaker == null ? new LlmProperties.CircuitBreaker(5, Duration.ofSeconds(60)) : circuitBreaker;
    }

    public boolean fixture() {
        return "fixture".equals(baseUrl);
    }

    /** Whether the env var named by {@code api-key-env} holds a value (the value itself is never exposed). */
    public boolean keyPresent() {
        if (fixture()) {
            return true;
        }
        String v = apiKeyEnv == null || apiKeyEnv.isBlank() ? null : System.getenv(apiKeyEnv);
        return v != null && !v.isBlank();
    }
}
