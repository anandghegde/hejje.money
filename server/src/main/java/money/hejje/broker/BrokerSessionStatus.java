package money.hejje.broker;

import java.time.Instant;

/**
 * PRD section 40 status shape.
 *
 * @param broker        broker code
 * @param state         session state
 * @param brokerUserId  broker user id when connected
 * @param establishedAt when the session was established
 * @param expiresAt     when the broker will expire the session
 * @param lastCheckedAt last successful validation
 * @param detail        human-readable detail
 * @param liveTradingEnabled false whenever the state is not CONNECTED
 */
public record BrokerSessionStatus(String broker, BrokerSessionState state, String brokerUserId, Instant establishedAt,
        Instant expiresAt, Instant lastCheckedAt, String detail, boolean liveTradingEnabled) {
}
