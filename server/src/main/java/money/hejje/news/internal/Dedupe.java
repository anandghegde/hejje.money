package money.hejje.news.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Normalisation and similarity used to spot the same story twice. Pure. */
public final class Dedupe {

    private Dedupe() {
    }

    /** Lower-case, punctuation stripped, whitespace collapsed. */
    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    /** SHA-256 of the normalised title and summary. */
    public static String hash(String title, String summary) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(normalize(title).getBytes(StandardCharsets.UTF_8));
            d.update((byte) 0);
            d.update(normalize(summary).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Jaccard overlap of the word sets of two normalised titles (0..1). */
    public static double similarity(String normA, String normB) {
        Set<String> a = new HashSet<>(Arrays.asList(normA.split(" ")));
        Set<String> b = new HashSet<>(Arrays.asList(normB.split(" ")));
        a.remove("");
        b.remove("");
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
