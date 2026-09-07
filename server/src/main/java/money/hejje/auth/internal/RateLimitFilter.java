package money.hejje.auth.internal;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.auth.AuthProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token-bucket rate limiting per principal (per client IP when unauthenticated). Non-GET requests under the
 * configured transactional path prefixes use the stricter bucket. Rejections are 429 with {@code Retry-After}.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final AuthProperties.RateLimit limits;
    private final ProblemAuthHandlers problems;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimitFilter(AuthProperties properties, ProblemAuthHandlers problems) {
        this.limits = properties.rateLimit();
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean transactional = isTransactional(request);
        String key = principalKey(request) + (transactional ? ":tx" : ":default");
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(transactional));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0));
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
    }

    private Bucket newBucket(boolean transactional) {
        int rate = transactional ? limits.transactionalRate() : limits.defaultRate();
        int burst = transactional ? limits.transactionalBurst() : limits.defaultBurst();
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(burst).refillGreedy(rate, Duration.ofSeconds(1)).build())
                .build();
    }

    private boolean isTransactional(HttpServletRequest request) {
        if ("GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return limits.transactionalPaths().stream().anyMatch(path::startsWith);
    }

    private static String principalKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof HejjeAuthentication hejje) {
            return "p:" + hejje.getPrincipal().id();
        }
        if (auth != null && auth.isAuthenticated()) {
            return "n:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
