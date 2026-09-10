package money.hejje.broker;

import java.time.Instant;
import java.util.UUID;

/**
 * A broker account Hejje has logged in to (plan M5.6). At most one is {@code active}: the transactional account orders
 * go to (enforced by a partial unique index); the others are known but not traded.
 */
public record BrokerAccount(UUID id, String broker, String accountId, String label, boolean active, Instant createdAt, Instant updatedAt,
        Instant activatedAt, String activatedBy) {
}
