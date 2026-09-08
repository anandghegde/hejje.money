package money.hejje.broker.zerodha;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.broker.BrokerInstrument;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OptionType;

/**
 * Parses the Kite Connect instruments CSV ({@code GET /instruments}) into normalized {@link BrokerInstrument}s.
 * Columns: instrument_token, exchange_token, tradingsymbol, name, last_price, expiry, strike, tick_size, lot_size,
 * instrument_type, segment, exchange. Rows on exchanges Hejje does not model (CDS, BCD, ...) are skipped.
 */
public final class KiteInstrumentCsv {

    private KiteInstrumentCsv() {
    }

    public static List<BrokerInstrument> parse(Reader reader) {
        try (BufferedReader in = reader instanceof BufferedReader b ? b : new BufferedReader(reader)) {
            String headerLine = in.readLine();
            if (headerLine == null) {
                return List.of();
            }
            List<String> header = splitCsv(headerLine);
            List<BrokerInstrument> out = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> cells = splitCsv(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size() && i < cells.size(); i++) {
                    row.put(header.get(i), cells.get(i));
                }
                toInstrument(row).ifPresent(out::add);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Normalizes one Kite row; empty when the exchange or instrument type is not one Hejje models. */
    static Optional<BrokerInstrument> toInstrument(Map<String, String> row) {
        String kiteExchange = row.getOrDefault("exchange", "");
        String segment = row.getOrDefault("segment", "");
        String kiteType = row.getOrDefault("instrument_type", "");
        String tradingSymbol = row.getOrDefault("tradingsymbol", "");
        String name = blankToNull(row.get("name"));
        int lotSize = parseInt(row.get("lot_size"), 1);
        BigDecimal tickSize = parseDecimal(row.get("tick_size"));
        if (tickSize == null || tickSize.signum() <= 0) {
            tickSize = new BigDecimal("0.05");
        }
        LocalDate expiry = parseDate(row.get("expiry"));
        BigDecimal strike = parseDecimal(row.get("strike"));

        if ("INDICES".equals(segment)) {
            return Optional.of(new BrokerInstrument(row.get("instrument_token"), tradingSymbol, kiteExchange, Exchange.INDEX,
                    tradingSymbol, name != null ? name : tradingSymbol, InstrumentType.INDEX, null, null, null, null, 1,
                    tickSize, null, row));
        }
        Exchange exchange;
        try {
            exchange = Exchange.valueOf(kiteExchange);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        switch (kiteType) {
            case "EQ" -> {
                return Optional.of(new BrokerInstrument(row.get("instrument_token"), tradingSymbol, kiteExchange, exchange,
                        tradingSymbol, name, InstrumentType.EQ, null, null, null, null, Math.max(1, lotSize), tickSize, null, row));
            }
            case "FUT" -> {
                if (name == null || expiry == null) {
                    return Optional.empty();
                }
                return Optional.of(new BrokerInstrument(row.get("instrument_token"), tradingSymbol, kiteExchange, exchange,
                        name, name, InstrumentType.FUT, name, expiry, null, null, Math.max(1, lotSize), tickSize, null, row));
            }
            case "CE", "PE" -> {
                if (name == null || expiry == null || strike == null || strike.signum() <= 0) {
                    return Optional.empty();
                }
                return Optional.of(new BrokerInstrument(row.get("instrument_token"), tradingSymbol, kiteExchange, exchange,
                        name, name, InstrumentType.OPT, name, expiry, strike.setScale(2, RoundingMode.UNNECESSARY),
                        OptionType.valueOf(kiteType), Math.max(1, lotSize), tickSize, null, row));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /** RFC 4180-style split: commas separate fields, double quotes wrap fields that contain commas or quotes. */
    static List<String> splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null || s.isBlank() ? fallback : new BigDecimal(s.trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return fallback;
        }
    }

    private static BigDecimal parseDecimal(String s) {
        try {
            if (s == null || s.isBlank()) {
                return null;
            }
            BigDecimal value = new BigDecimal(s.trim());
            return value.scale() > 2 ? value.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
