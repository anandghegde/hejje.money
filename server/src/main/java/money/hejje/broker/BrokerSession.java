package money.hejje.broker;

import java.time.Instant;

/** Result of a successful login. {@code accessToken} is secret and must only be stored encrypted. */
public record BrokerSession(String brokerUserId, String accessToken, String publicToken, Instant establishedAt) {

    @Override
    public String toString() {
        return "BrokerSession[brokerUserId=" + brokerUserId + ", establishedAt=" + establishedAt + "]";
    }
}
