package money.hejje.broker.dhan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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
 * Parses Dhan's compact security master ({@code api-scrip-master.csv}) into broker instruments on Hejje's symbols:
 * NSE/BSE equities (NSE series EQ), NSE/BSE index futures and options, stock futures and options, and the indices
 * Hejje watches under their Kite names ("NIFTY" → "NIFTY 50"). The broker token is {@code <segment>:<securityId>}
 * because a security id is only unique within its exchange segment. {@code SEM_TICK_SIZE} is in paise (USDINR 0.2500 =
 * ₹0.0025, an NSE equity 5.0000 = ₹0.05).
 */
final class DhanInstrumentCsv {

    static final Map<String, String> INDEX_ALIASES = Map.of("NIFTY", "NIFTY 50", "BANKNIFTY", "NIFTY BANK", "FINNIFTY", "NIFTY FIN SERVICE",
            "INDIA VIX", "INDIA VIX", "INDIAVIX", "INDIA VIX", "MIDCPNIFTY", "NIFTY MID SELECT", "SENSEX", "SENSEX");

    private DhanInstrumentCsv() {
    }

    static List<BrokerInstrument> parse(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return List.of();
            }
            String[] header = split(headerLine.replace("﻿", ""));
            List<BrokerInstrument> out = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = split(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++) {
                    row.put(header[i].trim(), i < cells.length ? cells[i].trim() : "");
                }
                toInstrument(row).ifPresent(out::add);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One row as a broker instrument; empty for segments and types Hejje does not model. */
    static Optional<BrokerInstrument> toInstrument(Map<String, String> row) {
        String exch = row.getOrDefault("SEM_EXM_EXCH_ID", "");
        String kind = row.getOrDefault("SEM_INSTRUMENT_NAME", "");
        String id = row.getOrDefault("SEM_SMST_SECURITY_ID", "");
        String tradingSymbol = row.getOrDefault("SEM_TRADING_SYMBOL", "");
        String underlying = blankToNull(row.get("SM_SYMBOL_NAME"));
        if (id.isEmpty() || tradingSymbol.isEmpty() || !("NSE".equals(exch) || "BSE".equals(exch))) {
            return Optional.empty();
        }
        boolean nse = "NSE".equals(exch);
        int lot = Math.max(1, (int) Math.round(parseDouble(row.get("SEM_LOT_UNITS"), 1)));
        BigDecimal tick = tick(row.get("SEM_TICK_SIZE"));
        LocalDate expiry = parseDate(row.get("SEM_EXPIRY_DATE"));
        switch (kind) {
            case "INDEX" -> {
                String symbol = INDEX_ALIASES.getOrDefault(tradingSymbol.toUpperCase(), underlying != null ? underlying : tradingSymbol);
                return Optional.of(new BrokerInstrument("IDX_I:" + id, tradingSymbol, "IDX_I", Exchange.INDEX, symbol, symbol, InstrumentType.INDEX,
                        null, null, null, null, 1, tick, null, row));
            }
            case "EQUITY" -> {
                if (nse && !"EQ".equals(row.getOrDefault("SEM_SERIES", ""))) {
                    return Optional.empty();
                }
                String segment = nse ? "NSE_EQ" : "BSE_EQ";
                return Optional.of(new BrokerInstrument(segment + ":" + id, tradingSymbol, segment, nse ? Exchange.NSE : Exchange.BSE, tradingSymbol,
                        blankToNull(row.get("SEM_CUSTOM_SYMBOL")), InstrumentType.EQ, null, null, null, null, lot, tick, null, row));
            }
            case "FUTIDX", "FUTSTK" -> {
                String name = underlying != null ? underlying : prefix(tradingSymbol);
                if (expiry == null) {
                    return Optional.empty();
                }
                String segment = nse ? "NSE_FNO" : "BSE_FNO";
                return Optional.of(new BrokerInstrument(segment + ":" + id, tradingSymbol, segment, nse ? Exchange.NFO : Exchange.BFO, name, name,
                        InstrumentType.FUT, name, expiry, null, null, lot, tick, null, row));
            }
            case "OPTIDX", "OPTSTK" -> {
                String name = underlying != null ? underlying : prefix(tradingSymbol);
                BigDecimal strike = parseDecimal(row.get("SEM_STRIKE_PRICE"));
                String type = row.getOrDefault("SEM_OPTION_TYPE", "");
                if (expiry == null || strike == null || strike.signum() <= 0 || !("CE".equals(type) || "PE".equals(type))) {
                    return Optional.empty();
                }
                String segment = nse ? "NSE_FNO" : "BSE_FNO";
                return Optional.of(new BrokerInstrument(segment + ":" + id, tradingSymbol, segment, nse ? Exchange.NFO : Exchange.BFO, name, name,
                        InstrumentType.OPT, name, expiry, strike.setScale(2, RoundingMode.HALF_UP), OptionType.valueOf(type), lot, tick, null, row));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private static BigDecimal tick(String paise) {
        BigDecimal v = parseDecimal(paise);
        if (v == null || v.signum() <= 0) {
            return new BigDecimal("0.05");
        }
        return v.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static String prefix(String tradingSymbol) {
        int dash = tradingSymbol.indexOf('-');
        return dash > 0 ? tradingSymbol.substring(0, dash) : tradingSymbol;
    }

    private static String[] split(String line) {
        return line.split(",", -1);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static BigDecimal parseDecimal(String s) {
        try {
            return s == null || s.isBlank() ? null : new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10 || s.startsWith("0001")) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
