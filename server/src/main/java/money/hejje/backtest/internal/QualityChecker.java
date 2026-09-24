package money.hejje.backtest.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestSpec;
import money.hejje.backtest.BacktestTrade;
import money.hejje.backtest.InstrumentMeta;
import money.hejje.backtest.QualityWarning;
import money.hejje.backtest.QualityWarning.Severity;
import money.hejje.backtest.Split;
import money.hejje.common.InstrumentType;
import money.hejje.market.Candle;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.dsl.Condition;
import money.hejje.strategy.dsl.Expr;

/** PRD section 12.3 quality warnings (docs/backtesting.md, "Quality warnings"). */
public final class QualityChecker {

    public static final int MIN_TRADES_WARN = 100;
    public static final int MIN_TRADES_FAIL = 30;

    public List<QualityWarning> check(StrategyDefinition def, BacktestSpec spec, List<BacktestTrade> trades, Map<Split, BacktestMetrics> bySplit,
            int sessionsExpected, int sessionsWithData, Map<UUID, InstrumentMeta> instruments, Map<UUID, List<Candle>> candles) {
        List<QualityWarning> out = new ArrayList<>();
        int n = trades.size();
        if (n < MIN_TRADES_FAIL) {
            out.add(new QualityWarning("INSUFFICIENT_SAMPLE", Severity.FAIL,
                    n + " trades; at least " + MIN_TRADES_FAIL + " are needed before a version can be validated", Map.of("trades", n)));
        } else if (n < MIN_TRADES_WARN) {
            out.add(new QualityWarning("LOW_SAMPLE", Severity.WARN, n + " trades; results are noisy below " + MIN_TRADES_WARN, Map.of("trades", n)));
        }

        long netProfit = trades.stream().mapToLong(t -> t.netPnl().paise()).sum();
        if (netProfit > 0 && n >= 5) {
            long top5 = trades.stream().mapToLong(t -> t.netPnl().paise()).filter(p -> p > 0).sorted().skip(Math.max(0,
                    trades.stream().filter(t -> t.netPnl().paise() > 0).count() - 5)).sum();
            double share = (double) top5 / netProfit;
            if (share > 0.5) {
                out.add(new QualityWarning("CONCENTRATED", Severity.WARN,
                        String.format("top 5 trades make %.0f%% of net profit", share * 100), Map.of("top5SharePct", Math.round(share * 100))));
            }
        }

        if (sessionsExpected > 0) {
            int missing = sessionsExpected - sessionsWithData;
            double pct = (double) missing / sessionsExpected;
            if (pct > 0.02) {
                out.add(new QualityWarning("MISSING_DATA", Severity.WARN,
                        missing + " of " + sessionsExpected + " expected sessions have no candles", Map.of("missing", missing, "expected", sessionsExpected)));
            }
        }

        java.util.Set<String> micro = microstructure(def);
        if (!micro.isEmpty()) {
            out.add(new QualityWarning("MICROSTRUCTURE_NOT_READY", Severity.WARN, "the rules use " + String.join(", ", micro)
                    + ", which need order-book or tick-flow data; candle history has none, so those conditions never pass in this backtest",
                    Map.of("indicators", List.copyOf(micro))));
        }

        int parameters = parameterCount(def);
        if (parameters > 0 && n > 0 && (double) n / parameters < 10) {
            out.add(new QualityWarning("OVERFIT_RISK", Severity.WARN,
                    n + " trades for " + parameters + " tunable parameters (fewer than 10 per parameter)", Map.of("parameters", parameters, "trades", n)));
        }

        // fills at bar extremes and zero-volume bars
        Map<UUID, Map<java.time.Instant, Candle>> byTime = new HashMap<>();
        candles.forEach((id, list) -> {
            Map<java.time.Instant, Candle> m = new HashMap<>();
            list.forEach(c -> m.put(c.openTime(), c));
            byTime.put(id, m);
        });
        int extremes = 0;
        int zeroVolume = 0;
        for (BacktestTrade t : trades) {
            Map<java.time.Instant, Candle> m = byTime.get(t.instrumentId());
            Candle entryBar = m == null ? null : m.get(t.entryTime());
            if (entryBar == null) {
                continue;
            }
            if (t.entryPrice().compareTo(entryBar.high()) >= 0 || t.entryPrice().compareTo(entryBar.low()) <= 0) {
                extremes++;
            }
            InstrumentMeta meta = instruments.get(t.instrumentId());
            if (entryBar.volume() == 0 && (meta == null || meta.type() != InstrumentType.INDEX)) {
                zeroVolume++;
            }
        }
        if (n > 0 && (double) extremes / n > 0.10) {
            out.add(new QualityWarning("UNREALISTIC_FILLS", Severity.WARN, extremes + " of " + n + " entries filled at the bar's high or low",
                    Map.of("atExtremes", extremes)));
        }
        if (zeroVolume > 0) {
            out.add(new QualityWarning("ZERO_VOLUME_BARS", Severity.WARN, zeroVolume + " entries on bars with no volume (illiquid or synthetic)",
                    Map.of("zeroVolumeEntries", zeroVolume)));
        }

        BacktestMetrics is = bySplit.get(Split.IN_SAMPLE);
        BacktestMetrics oos = bySplit.get(Split.OUT_OF_SAMPLE);
        if (is != null && oos != null && is.totalTrades() > 0 && oos.totalTrades() > 0 && is.expectancyR() > 0
                && oos.expectancyR() < is.expectancyR() * 0.5) {
            out.add(new QualityWarning("IS_OOS_GAP", Severity.WARN,
                    String.format("out-of-sample expectancy %.2fR is less than half of in-sample %.2fR", oos.expectancyR(), is.expectancyR()),
                    Map.of("inSampleR", is.expectancyR(), "outOfSampleR", oos.expectancyR())));
        }
        return out;
    }

    /** Numeric literals and indicator arguments in the rules plus stop/target/trailing values: what could be tuned. */
    public static int parameterCount(StrategyDefinition def) {
        int count = 0;
        for (Condition c : def.entry().conditions()) {
            count += literals(c.lhs()) + literals(c.rhs());
        }
        if (def.exit() != null) {
            for (Condition c : def.exit().conditions()) {
                count += literals(c.lhs()) + literals(c.rhs());
            }
        }
        if (def.stop().value() != null) {
            count++;
        }
        if (def.target().value() != null) {
            count++;
        }
        if (def.trailingStop() != null) {
            count++;
        }
        return count;
    }

    /** The order-book and tick-flow indicators the rules reference (plan M9.4), sorted. */
    public static java.util.Set<String> microstructure(StrategyDefinition def) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        List<Condition> all = new ArrayList<>(def.entry().conditions());
        if (def.exit() != null) {
            all.addAll(def.exit().conditions());
        }
        for (Condition c : all) {
            indicatorNames(c.lhs(), out);
            indicatorNames(c.rhs(), out);
        }
        out.retainAll(money.hejje.strategy.dsl.IndicatorCatalog.MICROSTRUCTURE);
        return out;
    }

    private static void indicatorNames(Expr expr, java.util.Set<String> out) {
        switch (expr) {
            case Expr.IndicatorCall c -> out.add(c.name());
            case Expr.Binary b -> {
                indicatorNames(b.left(), out);
                indicatorNames(b.right(), out);
            }
            case Expr.Negate n -> indicatorNames(n.operand(), out);
            default -> {
            }
        }
    }

    private static int literals(Expr expr) {
        return switch (expr) {
            case Expr.NumberLiteral n -> 1;
            case Expr.IndicatorCall c -> c.args().size();
            case Expr.Binary b -> literals(b.left()) + literals(b.right());
            case Expr.Negate n -> literals(n.operand());
            case Expr.SeriesRef s -> 0;
        };
    }
}
