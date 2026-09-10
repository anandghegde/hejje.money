package money.hejje.execution;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The executor lease ({@code hejje.execution.lease.*}, PRD 43, plan M5.6): the active executor renews it every
 * {@code heartbeat}; a standby takes it over once it has not been renewed for {@code ttl}, or at once after a
 * controlled failover, after which the old active does not contend for {@code failoverHold}.
 *
 * @param instance a readable name for this instance in status and audit (default: host name)
 */
@ConfigurationProperties("hejje.execution.lease")
public record LeaseProperties(@DefaultValue("PT30S") Duration ttl, @DefaultValue("PT10S") Duration heartbeat, String instance,
        @DefaultValue("PT2M") Duration failoverHold) {

    public static LeaseProperties defaults() {
        return new LeaseProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), null, Duration.ofMinutes(2));
    }
}
