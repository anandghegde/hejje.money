package money.hejje.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings ({@code hejje.auth.*}).
 *
 * @param adminPassword   bootstrap password for the {@code admin} user, only read when no user exists
 * @param jwtSecret       HS256 secret (at least 32 bytes); generated per start when absent outside prod
 * @param accessTokenTtl  JWT lifetime
 * @param refreshTokenTtl refresh cookie lifetime
 * @param rateLimit       per-principal rate limits
 */
@ConfigurationProperties("hejje.auth")
public record AuthProperties(
        String adminPassword,
        String jwtSecret,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("12h") Duration refreshTokenTtl,
        @DefaultValue RateLimit rateLimit) {

    /**
     * @param defaultRate         sustained requests per second for ordinary calls
     * @param defaultBurst        bucket capacity for ordinary calls
     * @param transactionalRate   sustained requests per second for transactional calls
     * @param transactionalBurst  bucket capacity for transactional calls
     * @param transactionalPaths  path prefixes whose non-GET requests count as transactional
     */
    public record RateLimit(
            @DefaultValue("20") int defaultRate,
            @DefaultValue("40") int defaultBurst,
            @DefaultValue("5") int transactionalRate,
            @DefaultValue("5") int transactionalBurst,
            @DefaultValue({"/api/v1/orders", "/api/v1/positions", "/api/v1/risk", "/api/v1/auth/clients"})
            List<String> transactionalPaths) {
    }
}
