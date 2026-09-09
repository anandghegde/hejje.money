package money.hejje.backtest.internal;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import money.hejje.backtest.BacktestTrade;
import money.hejje.backtest.RegimeBreakdown;
import money.hejje.common.Money;
import money.hejje.regime.RegimeSnapshot;

/** Pure grouping of trades by the regime label of their entry session. */
public final class RegimeGrouping {

    private RegimeGrouping() {
    }

    public static RegimeBreakdown group(List<BacktestTrade> trades, Map<LocalDate, RegimeSnapshot> labels, RegimeSnapshot current, List<String> dims,
            ZoneId zone) {
        List<String> effective = dims == null || dims.isEmpty() ? RegimeBreakdown.DEFAULT_DIMS : dims.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
        for (String d : effective) {
            key(null, List.of(d)); // validates the dimension names
        }
        Map<String, List<BacktestTrade>> grouped = new LinkedHashMap<>();
        for (BacktestTrade t : trades) {
            RegimeSnapshot label = labels.get(t.entryTime().atZone(zone).toLocalDate());
            grouped.computeIfAbsent(key(label, effective), k -> new ArrayList<>()).add(t);
        }
        List<RegimeBreakdown.Bucket> buckets = new ArrayList<>();
        grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> buckets.add(bucket(e.getKey(), e.getValue())));
        String currentKey = current == null ? null : key(current, effective);
        boolean currentKnown = currentKey != null && !currentKey.contains("UNKNOWN");
        RegimeBreakdown.Similar similar = null;
        String note = null;
        if (!currentKnown) {
            note = "current regime unknown along " + String.join(" × ", effective);
        } else if (!grouped.containsKey(currentKey)) {
            note = "no backtest trades in the current regime (" + currentKey + ")";
        } else {
            RegimeBreakdown.Bucket b = bucket(currentKey, grouped.get(currentKey));
            similar = new RegimeBreakdown.Similar(currentKey, b.trades(), b.winRate(), b.expectancyR(), b.profitFactor(), b.netPnl(), trades.size(),
                    expectancy(trades));
        }
        return new RegimeBreakdown(effective, buckets, similar, note);
    }

    static String key(RegimeSnapshot s, List<String> dims) {
        List<String> parts = new ArrayList<>();
        for (String d : dims) {
            parts.add(switch (d) {
                case "trend" -> s == null ? "UNKNOWN" : s.trend().name();
                case "volatility" -> s == null ? "UNKNOWN" : s.volatility().name();
                case "opening" -> s == null ? "UNKNOWN" : s.opening().name();
                case "breadth" -> s == null ? "UNKNOWN" : s.breadth().name();
                case "structure", "intradaystructure" -> s == null ? "UNKNOWN" : s.intradayStructure().name();
                case "event", "eventenvironment" -> s == null ? "UNKNOWN" : s.eventEnvironment().name();
                default -> throw new IllegalArgumentException("Unknown regime dimension '" + d + "' (trend, volatility, opening, breadth, structure, event)");
            });
        }
        return String.join(" × ", parts);
    }

    private static RegimeBreakdown.Bucket bucket(String key, List<BacktestTrade> trades) {
        int wins = 0;
        Money net = Money.ZERO;
        long grossWin = 0;
        long grossLoss = 0;
        for (BacktestTrade t : trades) {
            net = net.plus(t.netPnl());
            if (t.isWin()) {
                wins++;
                grossWin += t.netPnl().paise();
            } else {
                grossLoss += Math.abs(t.netPnl().paise());
            }
        }
        Double pf;
        if (grossLoss == 0) {
            pf = grossWin > 0 ? null : Double.valueOf(0.0); // no losses: profit factor undefined (null), or 0 with no wins either
        } else {
            pf = (double) grossWin / grossLoss;
        }
        return new RegimeBreakdown.Bucket(key, trades.size(), trades.isEmpty() ? 0 : (double) wins / trades.size(), expectancy(trades), pf, net);
    }

    private static double expectancy(List<BacktestTrade> trades) {
        return trades.isEmpty() ? 0 : trades.stream().mapToDouble(BacktestTrade::rMultiple).average().orElse(0);
    }
}
