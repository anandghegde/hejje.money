package money.hejje.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 webhook signatures (plan M5.5): {@code sha256=hex(HMAC(secret, timestamp + "." + body))}, compared in constant time. */
public final class WebhookSignatures {

    private WebhookSignatures() {
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    public static boolean verify(String secret, String timestamp, byte[] body, String signature) {
        if (signature == null || timestamp == null) {
            return false;
        }
        return constantTimeEquals(sign(secret, timestamp, body), signature.trim().toLowerCase());
    }

    public static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Unix seconds (or milliseconds) or an ISO-8601 instant/offset date-time; null when unreadable. */
    public static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.chars().allMatch(Character::isDigit)) {
            long n = Long.parseLong(v);
            return n > 100_000_000_000L ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(v).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    public static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
