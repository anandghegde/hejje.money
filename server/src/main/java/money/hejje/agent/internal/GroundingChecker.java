package money.hejje.agent.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import money.hejje.agent.Grounding;

/**
 * Checks an answer against the turn's tool outputs (plan M4.3): every cited id must appear in them, and every number
 * must be one of theirs, allowing the answer to round it or to express a fraction as a percentage. Dates, times, ids
 * and bare integers below 10 (counts, ordinals, list markers) are not treated as claims. Pure.
 */
public final class GroundingChecker {

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})?)?|(?<!\\d)\\d{1,2}:\\d{2}(?::\\d{2})?(?!\\d)");
    /** A number in prose: Indian or western digit grouping, or plain; not glued to letters (v2, M5, Q2 are labels). */
    private static final Pattern CLAIM = Pattern.compile(
            "(?<![\\p{L}\\d_.,])[-+−]?(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d+)?(?![\\d,]\\d)");
    private static final Pattern ANY_NUMBER = Pattern.compile("-?(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");

    private GroundingChecker() {
    }

    public static Grounding check(String answer, List<String> toolOutputs) {
        String evidence = String.join("\n", toolOutputs);
        Set<String> evidenceIds = new HashSet<>();
        Matcher ids = UUID.matcher(evidence);
        while (ids.find()) {
            evidenceIds.add(ids.group().toLowerCase(Locale.ROOT));
        }
        Set<String> cited = new LinkedHashSet<>();
        Matcher answerIds = UUID.matcher(answer);
        while (answerIds.find()) {
            cited.add(answerIds.group().toLowerCase(Locale.ROOT));
        }
        List<String> unknownIds = cited.stream().filter(id -> !evidenceIds.contains(id)).toList();

        Set<BigDecimal> known = new HashSet<>();
        Matcher n = ANY_NUMBER.matcher(strip(evidence));
        while (n.find()) {
            BigDecimal v = parse(n.group());
            if (v != null) {
                known.add(v);
            }
        }
        Set<String> verified = new LinkedHashSet<>();
        Set<String> unverified = new LinkedHashSet<>();
        Matcher claims = CLAIM.matcher(strip(answer));
        while (claims.find()) {
            String text = claims.group();
            BigDecimal value = parse(text);
            if (value == null || (!text.contains(".") && value.abs().compareTo(BigDecimal.TEN) < 0)) {
                continue;
            }
            (supported(value, known) ? verified : unverified).add(text.replace("−", "-").replaceFirst("^\\+", ""));
        }
        return new Grounding(new ArrayList<>(verified), new ArrayList<>(unverified), new ArrayList<>(cited), unknownIds);
    }

    private static String strip(String text) {
        return DATE_TIME.matcher(UUID.matcher(text).replaceAll(" ")).replaceAll(" ");
    }

    private static BigDecimal parse(String text) {
        try {
            return new BigDecimal(text.replace(",", "").replace("−", "-").replaceFirst("^\\+", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Equal, equal after rounding to the claim's precision, or a fraction shown as a percentage. Signs are ignored ("a loss of 1,200"). */
    static boolean supported(BigDecimal claim, Set<BigDecimal> known) {
        BigDecimal c = claim.abs();
        int scale = Math.max(0, claim.stripTrailingZeros().scale() < 0 ? 0 : claim.scale());
        for (BigDecimal k : known) {
            BigDecimal a = k.abs();
            if (a.compareTo(c) == 0 || a.setScale(scale, RoundingMode.HALF_UP).compareTo(c) == 0
                    || a.movePointRight(2).setScale(scale, RoundingMode.HALF_UP).compareTo(c) == 0) {
                return true;
            }
        }
        return false;
    }
}
