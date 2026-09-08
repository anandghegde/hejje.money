package money.hejje.broker;

import java.util.List;

/** The logged-in broker account. */
public record BrokerProfile(String brokerUserId, String userName, String email, List<String> exchanges, List<String> products) {
}
