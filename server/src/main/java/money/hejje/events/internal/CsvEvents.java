package money.hejje.events.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import money.hejje.events.EventScope;
import money.hejje.events.EventType;
import money.hejje.events.MarketEvent;
import money.hejje.instruments.Instrument;

/**
 * The manual path that always works: a CSV with a header line and the columns
 * {@code type, symbol, title, date, time, end_date, confidence} (symbol/time/end_date/confidence optional, any column
 * order). Corporate actions, board meetings with their purpose and results dates all fit.
 */
public final class CsvEvents {

    private CsvEvents() {
    }

    public record Parsed(List<MarketEvent> events, List<String> errors) {}

    public static Parsed parse(String csv, Function<String, Optional<Instrument>> resolver, ZoneId zone, Instant importedAt, String source) {
        List<MarketEvent> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            errors.add("empty input");
            return new Parsed(events, errors);
        }
        String[] header = split(lines[0]);
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            col.put(header[i].trim().toLowerCase(Locale.ROOT), i);
        }
        if (!col.containsKey("type") || !col.containsKey("date") || !col.containsKey("title")) {
            errors.add("header must contain type, title and date");
            return new Parsed(events, errors);
        }
        for (int line = 1; line < lines.length; line++) {
            if (lines[line].isBlank()) {
                continue;
            }
            String[] f = split(lines[line]);
            try {
                EventType type = EventType.valueOf(get(f, col, "type").toUpperCase(Locale.ROOT));
                LocalDate date = LocalDate.parse(get(f, col, "date"));
                String time = get(f, col, "time");
                String end = get(f, col, "end_date");
                String conf = get(f, col, "confidence");
                String symbol = get(f, col, "symbol");
                symbol = symbol.isEmpty() ? null : symbol.toUpperCase(Locale.ROOT);
                Instrument instrument = symbol == null ? null : resolver.apply(symbol).orElse(null);
                if (symbol != null && instrument == null && !type.isMarketScope()) {
                    errors.add("line " + (line + 1) + ": unknown symbol " + symbol);
                    continue;
                }
                Map<String, Object> raw = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> c : col.entrySet()) {
                    if (c.getValue() < f.length && !f[c.getValue()].isBlank()) {
                        raw.put(c.getKey(), f[c.getValue()].trim());
                    }
                }
                events.add(Events.on(type, instrument != null ? EventScope.INSTRUMENT : EventScope.MARKET, instrument == null ? null : instrument.id(), symbol,
                        get(f, col, "title"), date, time.isEmpty() ? null : LocalTime.parse(time), end.isEmpty() ? null : LocalDate.parse(end), source,
                        conf.isEmpty() ? 0.9 : Double.parseDouble(conf), raw, zone, importedAt));
            } catch (RuntimeException e) {
                errors.add("line " + (line + 1) + ": " + e.getMessage());
            }
        }
        return new Parsed(events, errors);
    }

    private static String get(String[] f, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= f.length ? "" : f[i].trim();
    }

    /** Minimal CSV split: commas, double-quoted fields with doubled quotes. */
    static String[] split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(String[]::new);
    }
}
