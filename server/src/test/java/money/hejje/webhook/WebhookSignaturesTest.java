package money.hejje.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignaturesTest {

    static final String SECRET = "whsec_test";
    static final byte[] BODY = "{\"instrument\":\"NSE:INFY\",\"direction\":\"BUY\",\"stop\":\"1490\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void aSignatureOverTimestampAndBodyVerifies() {
        String sig = WebhookSignatures.sign(SECRET, "1789000000", BODY);
        assertThat(sig).startsWith("sha256=").hasSize(7 + 64);
        assertThat(WebhookSignatures.verify(SECRET, "1789000000", BODY, sig)).isTrue();
        assertThat(WebhookSignatures.verify(SECRET, "1789000000", BODY, " " + sig.toUpperCase().replace("SHA256=", "sha256=") + " ")).isTrue();
    }

    @Test
    void aTamperedBodyTimestampOrSecretFails() {
        String sig = WebhookSignatures.sign(SECRET, "1789000000", BODY);
        byte[] tampered = new String(BODY, StandardCharsets.UTF_8).replace("1490", "1400").getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSignatures.verify(SECRET, "1789000000", tampered, sig)).isFalse();
        assertThat(WebhookSignatures.verify(SECRET, "1789000001", BODY, sig)).isFalse();
        assertThat(WebhookSignatures.verify("whsec_other", "1789000000", BODY, sig)).isFalse();
        assertThat(WebhookSignatures.verify(SECRET, "1789000000", BODY, null)).isFalse();
        assertThat(WebhookSignatures.verify(SECRET, null, BODY, sig)).isFalse();
    }

    @Test
    void timestampsAreUnixSecondsMillisOrIso() {
        assertThat(WebhookSignatures.parseTimestamp("1789000000")).isEqualTo(Instant.ofEpochSecond(1_789_000_000L));
        assertThat(WebhookSignatures.parseTimestamp("1789000000123")).isEqualTo(Instant.ofEpochMilli(1_789_000_000_123L));
        assertThat(WebhookSignatures.parseTimestamp("2026-09-10T04:05:06Z")).isEqualTo(Instant.parse("2026-09-10T04:05:06Z"));
        assertThat(WebhookSignatures.parseTimestamp("2026-09-10T09:35:06+05:30")).isEqualTo(Instant.parse("2026-09-10T04:05:06Z"));
        assertThat(WebhookSignatures.parseTimestamp("yesterday")).isNull();
        assertThat(WebhookSignatures.parseTimestamp(" ")).isNull();
    }

    @Test
    void passphrasesCompareInConstantTime() {
        assertThat(WebhookSignatures.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(WebhookSignatures.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(WebhookSignatures.constantTimeEquals("abc", null)).isFalse();
    }
}
