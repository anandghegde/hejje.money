package money.hejje.common.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks secret values in log text: {@code access_token=abc} becomes {@code access_token=***}. */
public final class LogRedactor {

    public static final String MASK = "***";

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key|api[_-]?secret|access[_-]?token|refresh[_-]?token|password|authorization)"
                    + "(\"?\\s*[:=]\\s*\"?)((?:Bearer|Basic)\\s+)?([^\\s,;&\"'}\\]]+)");

    private LogRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = SECRET.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        do {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + m.group(2) + MASK));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }
}
