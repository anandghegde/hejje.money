package money.hejje.backtest.experiments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a variant delta to a definition tree: a JSON merge patch (RFC 7396: nested objects merge, lists and scalars
 * replace, {@code null} removes a key) plus two conveniences, {@code entry_add} and {@code exit_add}, that append conditions
 * to the entry (or exit) rule list without restating it. A delta may not rename the strategy. Pure.
 */
public final class VariantDelta {

    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?");

    private VariantDelta() {
    }

    public static Map<String, Object> apply(Map<String, Object> base, Map<String, Object> delta) {
        if (delta == null || delta.isEmpty()) {
            return copy(base);
        }
        if (delta.containsKey("name")) {
            throw new IllegalArgumentException("A variant cannot rename the strategy (remove \"name\" from the delta)");
        }
        Map<String, Object> patch = new LinkedHashMap<>(delta);
        Object entryAdd = patch.remove("entry_add");
        Object exitAdd = patch.remove("exit_add");
        Map<String, Object> out = merge(copy(base), patch);
        if (entryAdd != null) {
            append(out, "entry", entryAdd, "all");
        }
        if (exitAdd != null) {
            append(out, "exit", exitAdd, "any");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (e.getValue() == null) {
                target.remove(e.getKey());
            } else if (e.getValue() instanceof Map<?, ?> m && target.get(e.getKey()) instanceof Map<?, ?> existing) {
                target.put(e.getKey(), merge(copy((Map<String, Object>) existing), (Map<String, Object>) m));
            } else {
                target.put(e.getKey(), e.getValue());
            }
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    private static void append(Map<String, Object> tree, String key, Object conditions, String defaultMode) {
        if (!(conditions instanceof List<?> list)) {
            throw new IllegalArgumentException(key + "_add must be a list of conditions");
        }
        Map<String, Object> rules = tree.get(key) instanceof Map<?, ?> m ? copy((Map<String, Object>) m) : new LinkedHashMap<>();
        String mode = rules.containsKey("all") ? "all" : rules.containsKey("any") ? "any" : defaultMode;
        List<Object> merged = rules.get(mode) instanceof List<?> existing ? new ArrayList<>(existing) : new ArrayList<>();
        merged.addAll(list);
        rules.put(mode, merged);
        tree.put(key, rules);
    }

    /** Every number in the definition by path (numbers inside condition text as {@code path#k}): the tunable parameters. */
    public static Map<String, Double> numbers(Map<String, Object> tree) {
        Map<String, Double> out = new TreeMap<>();
        walk("", tree, out);
        out.keySet().removeIf(k -> k.startsWith("version") || k.startsWith("trade_window") || k.startsWith("force_exit_time"));
        return out;
    }

    private static void walk(String path, Object node, Map<String, Double> out) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> walk(path.isEmpty() ? String.valueOf(k) : path + "." + k, v, out));
        } else if (node instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                walk(path + "[" + i + "]", l.get(i), out);
            }
        } else if (node instanceof Number n) {
            out.put(path, n.doubleValue());
        } else if (node instanceof String s && (path.startsWith("entry") || path.startsWith("exit"))) {
            Matcher matcher = NUMBER.matcher(s);
            int k = 0;
            while (matcher.find()) {
                out.put(path + "#" + k++, Double.parseDouble(matcher.group()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> inner ? copy((Map<String, Object>) inner) : v instanceof List<?> l ? new ArrayList<>(l) : v));
        return out;
    }
}
