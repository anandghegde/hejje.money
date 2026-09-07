package money.hejje.auth.internal;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** API key format: {@code hejje_<prefix>_<secret>}. Only the SHA-256 of the secret is stored. */
public final class ApiKeys {

    public static final String KEY_PREFIX = "hejje_";
    private static final Pattern FORMAT = Pattern.compile("^hejje_([a-z0-9]{8})_([A-Za-z0-9_-]{20,})$");

    /** A freshly generated key: plaintext shown once, hash stored. */
    public record Generated(String prefix, String plaintextKey, String secretHash) {}

    /** The two parts parsed from a presented key. */
    public record Parsed(String prefix, String secret) {}

    private ApiKeys() {
    }

    public static Generated generate() {
        String prefix = Secrets.randomPrefix(8);
        String secret = Secrets.randomToken(32);
        return new Generated(prefix, KEY_PREFIX + prefix + "_" + secret, Secrets.sha256Hex(secret));
    }

    public static boolean looksLikeKey(String token) {
        return token != null && token.startsWith(KEY_PREFIX);
    }

    public static Optional<Parsed> parse(String token) {
        Matcher m = FORMAT.matcher(token);
        return m.matches() ? Optional.of(new Parsed(m.group(1), m.group(2))) : Optional.empty();
    }

    public static boolean matches(Parsed parsed, String secretHash) {
        return Secrets.constantTimeEquals(Secrets.sha256Hex(parsed.secret()), secretHash);
    }
}
