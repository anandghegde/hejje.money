package money.hejje.common.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for secrets at rest (broker access tokens). Ciphertext format: {@code v1:} + base64(12-byte nonce ‖ ciphertext ‖ tag).
 */
@Component
public class TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    @Autowired
    TokenCipher(SecurityProperties properties, Environment environment) {
        this(resolveKey(properties.encryptionKey(), Arrays.asList(environment.getActiveProfiles()).contains("prod")));
    }

    public TokenCipher(byte[] keyBytes) {
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("Encryption key must be 32 bytes, got " + keyBytes.length);
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] resolveKey(String configured, boolean prod) {
        if (configured == null || configured.isBlank()) {
            if (prod) {
                throw new IllegalStateException("HEJJE_ENCRYPTION_KEY is required in prod (32 bytes, base64 or hex)");
            }
            log.warn("HEJJE_ENCRYPTION_KEY not set; using a random per-start key (stored broker tokens will not survive a restart)");
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            return random;
        }
        String value = configured.trim();
        if (value.matches("[0-9a-fA-F]{64}")) {
            return HexFormat.of().parseHex(value);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw new IllegalStateException("HEJJE_ENCRYPTION_KEY must be 32 bytes encoded as base64 or hex");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Unsupported ciphertext format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
            byte[] plain = cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed (wrong key or corrupted value)", e);
        }
    }
}
