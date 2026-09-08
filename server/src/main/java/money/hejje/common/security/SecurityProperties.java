package money.hejje.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Secrets-at-rest settings ({@code hejje.security.*}).
 *
 * @param encryptionKey AES key for tokens at rest: 32 bytes as base64 or hex (env {@code HEJJE_ENCRYPTION_KEY}).
 *                      Required in {@code prod}; a random per-start key is used otherwise.
 */
@ConfigurationProperties("hejje.security")
public record SecurityProperties(String encryptionKey) {
}
