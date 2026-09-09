package money.hejje.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Versioned prompt templates under {@code resources/prompts/<name>_v<n>.txt} with {@code {{placeholder}}} substitution, and the prompt hash used for logging. */
public final class Prompts {

    private Prompts() {
    }

    /** Loads {@code prompts/<file>} from the classpath. */
    public static String load(String file) {
        try (InputStream in = Prompts.class.getClassLoader().getResourceAsStream("prompts/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Prompt template prompts/" + file + " not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Prompt template prompts/" + file + " unreadable", e);
        }
    }

    public static String fill(String template, Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /** SHA-256 over system + user prompt (short hex), logged instead of the prompt text. */
    public static String hash(String systemPrompt, String userPrompt) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update((systemPrompt == null ? "" : systemPrompt).getBytes(StandardCharsets.UTF_8));
            d.update((byte) 0);
            d.update((userPrompt == null ? "" : userPrompt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
