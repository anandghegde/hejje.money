package money.hejje.ratings.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NSE's ASM and GSM reports, the JSON of {@code /api/reportASM} and {@code /api/reportGSM} (docs/ratings.md,
 * "Surveillance"). The stage is read from NSE's code: {@code LTASM - II (14)} is ASM_LT_2, {@code STASM - I (11)} is
 * ASM_ST_1, and a GSM code names its stage after "GSM" wherever it stands ({@code GSM - VI (6)}, {@code IBC - Receipt &
 * GSM 0 (62)}, {@code GSM IV & IBC - Receipt (66)}). A row whose code does not parse is skipped with a warning.
 */
final class SurveillanceParser {

    private static final Logger log = LoggerFactory.getLogger(SurveillanceParser.class);
    private static final Pattern ASM = Pattern.compile("^(LT|ST)ASM\\s*-\\s*([IVX]+)\\b");
    private static final Pattern GSM = Pattern.compile("\\bGSM\\s*(?:-\\s*)?(0|[IVX]+)\\b");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);

    record Flag(String symbol, String flag, String code) {
    }

    /** The flags of one report and the newest date printed on its rows (null when none parses). */
    record Report(LocalDate date, List<Flag> flags) {
    }

    private SurveillanceParser() {
    }

    static Report asm(JsonNode root) {
        List<Flag> flags = new ArrayList<>();
        LocalDate date = null;
        for (String section : List.of("longterm", "shortterm")) {
            JsonNode data = root.path(section).path("data");
            if (!data.isArray()) {
                throw new IllegalArgumentException("ASM report without " + section + ".data");
            }
            for (JsonNode row : data) {
                String code = row.path("survCode").asText("").trim();
                Matcher m = ASM.matcher(code);
                if (!m.find()) {
                    log.warn("ASM row {} has an unknown code '{}'; skipped", row.path("symbol").asText(), code);
                    continue;
                }
                flags.add(new Flag("NSE:" + row.path("symbol").asText().trim(), "ASM_" + m.group(1) + "_" + roman(m.group(2)), code));
                date = later(date, row.path("asmTime").asText(""));
            }
        }
        return new Report(date, flags);
    }

    static Report gsm(JsonNode root) {
        if (!root.isArray()) {
            throw new IllegalArgumentException("GSM report is not a list");
        }
        List<Flag> flags = new ArrayList<>();
        LocalDate date = null;
        for (JsonNode row : root) {
            String code = row.path("survCode").asText("").trim();
            Matcher m = GSM.matcher(code);
            if (!m.find()) {
                log.warn("GSM row {} has an unknown code '{}'; skipped", row.path("symbol").asText(), code);
                continue;
            }
            flags.add(new Flag("NSE:" + row.path("symbol").asText().trim(), "GSM_" + roman(m.group(1)), code));
            date = later(date, row.path("gsmTime").asText(""));
        }
        return new Report(date, flags);
    }

    /** One flag per symbol: GSM before long-term ASM before short-term ASM (NSE keeps the lists disjoint; this only breaks ties). */
    static Map<String, Flag> merge(Report gsm, Report asm) {
        Map<String, Flag> out = new LinkedHashMap<>();
        gsm.flags().forEach(f -> out.putIfAbsent(f.symbol(), f));
        asm.flags().stream().filter(f -> f.flag().startsWith("ASM_LT")).forEach(f -> out.putIfAbsent(f.symbol(), f));
        asm.flags().forEach(f -> out.putIfAbsent(f.symbol(), f));
        return out;
    }

    static int roman(String s) {
        int total = 0;
        int previous = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            total += v < previous ? -v : v;
            previous = Math.max(previous, v);
        }
        return total;
    }

    /** "25-Sep-2026" or "25-Sep-2026 08:08:02". */
    private static LocalDate later(LocalDate current, String text) {
        if (text.isBlank()) {
            return current;
        }
        try {
            LocalDate d = LocalDate.parse(text.trim().split("\\s+")[0], DATE);
            return current == null || d.isAfter(current) ? d : current;
        } catch (DateTimeParseException e) {
            return current;
        }
    }
}
