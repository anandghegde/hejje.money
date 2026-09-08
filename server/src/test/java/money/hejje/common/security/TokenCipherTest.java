package money.hejje.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void roundTripsAndHidesPlaintext() {
        TokenCipher cipher = new TokenCipher(key());
        String token = "kite-access-token-abc123";
        String encrypted = cipher.encrypt(token);
        assertThat(encrypted).startsWith("v1:").doesNotContain(token);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
        assertThat(cipher.encrypt(token)).isNotEqualTo(encrypted); // fresh nonce every time
    }

    @Test
    void wrongKeyFails() {
        String encrypted = new TokenCipher(key()).encrypt("secret");
        assertThatThrownBy(() -> new TokenCipher(key()).decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenCipher(key()).decrypt("plain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenCipher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }
}
