package money.hejje.webhook;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A webhook (plan M5.5). The secret is never part of this record: it is shown once on creation and rotation.
 *
 * @param strategyVersionId the mapped strategy version; null for MANUAL_EXTERNAL (manual order proposals)
 * @param allowedInstruments Hejje symbols the webhook may trade; empty = any the target allows
 */
public record Webhook(UUID id, String name, AuthMode authMode, UUID strategyVersionId, boolean enabled, List<String> allowedInstruments, Instant createdAt,
        String createdBy, Instant updatedAt, Instant lastReceivedAt) {

    public Webhook {
        allowedInstruments = allowedInstruments == null ? List.of() : List.copyOf(allowedInstruments);
    }

    /** HMAC: signed headers; PASSPHRASE: the secret and a timestamp in the body (TradingView, which cannot sign). */
    public enum AuthMode { HMAC, PASSPHRASE }

    public boolean manualExternal() {
        return strategyVersionId == null;
    }
}
