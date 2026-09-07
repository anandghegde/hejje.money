package money.hejje.system;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * System module settings ({@code hejje.system.*}).
 *
 * @param egress egress IP verification
 * @param clock  clock drift check
 */
@ConfigurationProperties("hejje.system")
public record SystemProperties(@DefaultValue Egress egress, @DefaultValue Clock clock) {

    /**
     * @param enabled       when false the check reports SKIPPED (dev/test default)
     * @param checkInterval how often to resolve the public IP
     * @param resolvers     HTTP endpoints that return the caller's public IP as plain text
     * @param timeout       per-resolver HTTP timeout
     */
    public record Egress(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5m") Duration checkInterval,
            @DefaultValue({"https://api.ipify.org", "https://checkip.amazonaws.com"}) List<String> resolvers,
            @DefaultValue("5s") Duration timeout) {
    }

    /**
     * @param enabled       when false the check reports SKIPPED
     * @param checkInterval how often to compare against the remote clock
     * @param host          HTTPS URL whose {@code Date} response header is the reference time
     * @param maxDrift      drift above which the clock is DEGRADED
     * @param timeout       HTTP timeout
     */
    public record Clock(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10m") Duration checkInterval,
            @DefaultValue("https://www.google.com") String host,
            @DefaultValue("2s") Duration maxDrift,
            @DefaultValue("5s") Duration timeout) {
    }
}
