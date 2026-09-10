package money.hejje.llm.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * One circuit breaker per provider. {@code failureThreshold} consecutive retryable failures (timeouts, 429, 5xx) open
 * the circuit for {@code openFor}; afterwards one trial call is let through (half-open): success closes it, a retryable
 * failure re-opens it. A non-retryable failure means the provider answered, so it does not count.
 */
public class ProviderCircuits {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Health(State state, int consecutiveFailures, Instant lastSuccessAt, Instant lastFailureAt, String lastError, Instant openUntil) {
    }

    private static final class Circuit {
        int failures;
        Instant openUntil;
        boolean trialInFlight;
        Instant lastSuccessAt;
        Instant lastFailureAt;
        String lastError;
    }

    private final int failureThreshold;
    private final Duration openFor;
    private final Map<String, Circuit> circuits = new HashMap<>();

    public ProviderCircuits(int failureThreshold, Duration openFor) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openFor = openFor;
    }

    /** Whether a call may go to the provider now (claims the single half-open trial). */
    public synchronized boolean allow(String provider, Instant now) {
        Circuit c = circuit(provider);
        if (c.openUntil == null) {
            return true;
        }
        if (now.isBefore(c.openUntil) || c.trialInFlight) {
            return false;
        }
        c.trialInFlight = true;
        return true;
    }

    public synchronized void success(String provider, Instant now) {
        Circuit c = circuit(provider);
        c.failures = 0;
        c.openUntil = null;
        c.trialInFlight = false;
        c.lastSuccessAt = now;
    }

    public synchronized void failure(String provider, Instant now, String error, boolean retryable) {
        Circuit c = circuit(provider);
        c.lastFailureAt = now;
        c.lastError = error;
        if (!retryable) {
            c.failures = 0;
            c.openUntil = null;
            c.trialInFlight = false;
            return;
        }
        c.failures++;
        if (c.trialInFlight || c.failures >= failureThreshold) {
            c.openUntil = now.plus(openFor);
            c.trialInFlight = false;
        }
    }

    public synchronized Health health(String provider, Instant now) {
        Circuit c = circuit(provider);
        State state = c.openUntil == null ? State.CLOSED : now.isBefore(c.openUntil) ? State.OPEN : State.HALF_OPEN;
        return new Health(state, c.failures, c.lastSuccessAt, c.lastFailureAt, c.lastError, c.openUntil);
    }

    private Circuit circuit(String provider) {
        return circuits.computeIfAbsent(provider, p -> new Circuit());
    }
}
