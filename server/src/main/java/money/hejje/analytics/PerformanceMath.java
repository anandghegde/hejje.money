package money.hejje.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import money.hejje.common.Money;

/**
 * Deterministic performance investigation over closed round trips (plan M4.5, PRD 57): loss attribution, slippage, rule
 * adherence, and counterfactuals that remove a filtered subset from the actual trade sequence. Every counterfactual is
 * labelled SIMULATED and carries the actual figures next to it. Pure; money in paise until the rupee outputs.
 */
public final class PerformanceMath {

    public static final String SIMULATED = "SIMULATED";

    public record Bucket(String key, int trades, int losers, BigDecimal netPnl, BigDecimal losses, BigDecimal lossSharePct) {}

    public record Dimension(String name, List<Bucket> buckets) {}

    /** Losses are the absolute net P&L of losing trades; a bucket's share is its losses over all losses. */
    public record LossAttribution(int trades, int winners, int losers, BigDecimal netPnl, BigDecimal grossLosses, BigDecimal grossWins, List<Dimension> dimensions,
            List<Bucket> familyByTrend, String headline) {}

    public record SlippageSide(int trades, Double meanBps, Double medianBps, Double p90Bps, Double worstBps, BigDecimal costRupees) {}

    public record StrategySlippage(String strategy, int trades, Double meanEntryBps, Double meanExitBps) {}

    /** Positive bps are worse for the trader; cost ≈ bps × price × quantity. */
    public record SlippageStats(SlippageSide entry, SlippageSide exit, BigDecimal totalCostRupees, List<StrategySlippage> byStrategy) {}

    public record StrategyAdherence(String strategy, int trades, Double meanAdherencePct, int setupInvalid) {}

    public record AdherenceStats(int trades, int withAdherence, Double meanAdherencePct, int fullAdherence, int setupInvalid, int manualExits,
            BigDecimal netFullAdherence, BigDecimal netPartialAdherence, List<StrategyAdherence> byStrategy) {}

    /**
     * Which trades to remove: a trade is excluded when it matches every category given (AND across categories, OR within
     * one), so {@code families=[MEAN_REVERSION], trends=[STRONG_UP]} removes mean-reversion trades on strong-up days only.
     */
    public record CounterfactualFilter(List<String> regimes, List<String> trends, List<String> families, List<String> strategies, List<String> events,
            List<String> news, List<Integer> hours, List<String> instruments) {

        public CounterfactualFilter {
            regimes = clean(regimes);
            trends = clean(trends);
            families = clean(families);
            strategies = clean(strategies);
            events = clean(events);
            news = clean(news);
            hours = hours == null ? List.of() : List.copyOf(hours);
            instruments = clean(instruments);
        }

        private static List<String> clean(List<String> values) {
            return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).toList();
        }

        public boolean isEmpty() {
            return regimes.isEmpty() && trends.isEmpty() && families.isEmpty() && strategies.isEmpty() && events.isEmpty() && news.isEmpty() && hours.isEmpty()
                    && instruments.isEmpty();
        }

        public boolean excludes(TradeFact f) {
            return !isEmpty() && in(regimes, f.regime()) && in(trends, f.trend()) && in(families, f.family()) && in(strategies, f.strategy())
                    && in(events, f.event()) && in(news, f.news()) && (hours.isEmpty() || hours.contains(f.hour())) && in(instruments, f.instrument());
        }

        private static boolean in(List<String> allowed, String value) {
            return allowed.isEmpty() || allowed.stream().anyMatch(a -> a.equalsIgnoreCase(value));
        }
    }

    public record Outcome(int trades, int winners, BigDecimal netPnl, BigDecimal maxDrawdown, Double winRate, Double profitFactor) {}

    public record Counterfactual(String basis, String note, CounterfactualFilter filter, Outcome actual, Outcome simulated, int excludedTrades,
            BigDecimal excludedNetPnl, BigDecimal netDifference, BigDecimal drawdownDifference) {}

    static final String NOTE = "Hypothetical: the actual trade sequence with the excluded trades removed. It does not model trades that might have been "
            + "taken instead, different fills or re-entries. Actual results are shown alongside.";

    private PerformanceMath() {
    }

    // ---- loss attribution

    public static LossAttribution attribute(List<TradeFact> facts) {
        long totalLoss = facts.stream().filter(f -> f.netPaise() < 0).mapToLong(f -> -f.netPaise()).sum();
        long totalWin = facts.stream().filter(f -> f.netPaise() > 0).mapToLong(TradeFact::netPaise).sum();
        List<Dimension> dims = List.of(
                new Dimension("family", buckets(facts, TradeFact::family, totalLoss)),
                new Dimension("strategy", buckets(facts, TradeFact::strategy, totalLoss)),
                new Dimension("trend", buckets(facts, TradeFact::trend, totalLoss)),
                new Dimension("regime", buckets(facts, TradeFact::regime, totalLoss)),
                new Dimension("event", buckets(facts, TradeFact::event, totalLoss)),
                new Dimension("news", buckets(facts, TradeFact::news, totalLoss)),
                new Dimension("exitReason", buckets(facts, TradeFact::exitReason, totalLoss)),
                new Dimension("cause", buckets(facts, TradeFact::cause, totalLoss)),
                new Dimension("entryTiming", buckets(facts, TradeFact::entryTiming, totalLoss)),
                new Dimension("hour", buckets(facts, f -> String.format("%02d", f.hour()), totalLoss)),
                new Dimension("instrument", buckets(facts, TradeFact::instrument, totalLoss)));
        List<Bucket> combos = buckets(facts, f -> f.family() + " × " + f.trend(), totalLoss);
        String headline = null;
        if (totalLoss > 0 && !combos.isEmpty() && combos.get(0).losses().signum() > 0) {
            Bucket top = combos.get(0);
            String[] parts = top.key().split(" × ", 2);
            headline = top.lossSharePct().stripTrailingZeros().toPlainString() + "% of losses came from " + words(parts[0]) + " strategies during " + words(parts[1])
                    + " sessions (" + top.losers() + " of " + top.trades() + " trades lost).";
        }
        int winners = (int) facts.stream().filter(f -> f.netPaise() > 0).count();
        int losers = (int) facts.stream().filter(f -> f.netPaise() < 0).count();
        return new LossAttribution(facts.size(), winners, losers, rupees(facts.stream().mapToLong(TradeFact::netPaise).sum()), rupees(totalLoss), rupees(totalWin),
                dims, combos, headline);
    }

    /**
     * Plan M9.7: the pace buckets of {@link PaceReport}. Days are IST dates of the entry; a trade's sequence number and its
     * day's count come from the facts given (so a strategy filter applies to both).
     */
    public static PaceReport pace(String mode, java.time.LocalDate from, java.time.LocalDate to, String strategy, List<TradeFact> facts, java.time.ZoneId zone) {
        Map<java.time.LocalDate, List<TradeFact>> byDay = new java.util.TreeMap<>();
        for (TradeFact f : facts) {
            byDay.computeIfAbsent(f.openedAt().atZone(zone).toLocalDate(), k -> new ArrayList<>()).add(f);
        }
        Map<String, List<TradeFact>> count = new LinkedHashMap<>();
        for (String b : List.of("1-4", "5-8", "9-16", "17+")) {
            count.put(b, new ArrayList<>());
        }
        Map<String, List<TradeFact>> sequence = new LinkedHashMap<>();
        for (String b : List.of("1st", "2nd", "3rd", "4th", "5th", "6th+")) {
            sequence.put(b, new ArrayList<>());
        }
        Map<String, List<TradeFact>> hour = new java.util.TreeMap<>();
        for (List<TradeFact> day : byDay.values()) {
            day.sort(Comparator.comparing(TradeFact::openedAt));
            int n = day.size();
            String dayBucket = n <= 4 ? "1-4" : n <= 8 ? "5-8" : n <= 16 ? "9-16" : "17+";
            for (int i = 0; i < n; i++) {
                TradeFact f = day.get(i);
                count.get(dayBucket).add(f);
                sequence.get(i >= 5 ? "6th+" : List.of("1st", "2nd", "3rd", "4th", "5th").get(i)).add(f);
                hour.computeIfAbsent(String.format("%02d", f.openedAt().atZone(zone).getHour()), k -> new ArrayList<>()).add(f);
            }
        }
        return new PaceReport(mode, from, to, strategy, facts.size(), paceRows(count), paceRows(sequence), paceRows(hour));
    }

    private static List<PaceReport.Row> paceRows(Map<String, List<TradeFact>> groups) {
        List<PaceReport.Row> out = new ArrayList<>();
        groups.forEach((k, list) -> {
            int wins = (int) list.stream().filter(f -> f.netPaise() > 0).count();
            List<Double> rs = list.stream().map(TradeFact::outcomeR).filter(Objects::nonNull).toList();
            long net = list.stream().mapToLong(TradeFact::netPaise).sum();
            out.add(new PaceReport.Row(k, list.size(), wins, list.isEmpty() ? null : Math.round(1000.0 * wins / list.size()) / 1000.0,
                    rs.isEmpty() ? null : Math.round(1000.0 * rs.stream().mapToDouble(Double::doubleValue).average().orElse(0)) / 1000.0, rs.size(),
                    list.isEmpty() ? null : BigDecimal.valueOf(net).divide(BigDecimal.valueOf(100L * list.size()), 2, RoundingMode.HALF_UP), rupees(net)));
        });
        return out;
    }

    static List<Bucket> buckets(List<TradeFact> facts, Function<TradeFact, String> key, long totalLoss) {
        Map<String, List<TradeFact>> groups = new LinkedHashMap<>();
        for (TradeFact f : facts) {
            groups.computeIfAbsent(Objects.requireNonNullElse(key.apply(f), "UNKNOWN"), k -> new ArrayList<>()).add(f);
        }
        List<Bucket> out = new ArrayList<>();
        groups.forEach((k, list) -> {
            long loss = list.stream().filter(f -> f.netPaise() < 0).mapToLong(f -> -f.netPaise()).sum();
            out.add(new Bucket(k, list.size(), (int) list.stream().filter(f -> f.netPaise() < 0).count(), rupees(list.stream().mapToLong(TradeFact::netPaise).sum()),
                    rupees(loss), totalLoss == 0 ? BigDecimal.ZERO.setScale(1) : BigDecimal.valueOf(loss * 100.0 / totalLoss).setScale(1, RoundingMode.HALF_UP)));
        });
        out.sort(Comparator.comparing(Bucket::losses).reversed().thenComparing(Bucket::key));
        return out;
    }

    private static String words(String label) {
        return label.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ---- slippage

    public static SlippageStats slippage(List<TradeFact> facts) {
        SlippageSide entry = side(facts, TradeFact::entrySlippageBps, TradeFact::entryPrice);
        SlippageSide exit = side(facts, TradeFact::exitSlippageBps, TradeFact::exitPrice);
        Map<String, List<TradeFact>> byStrategy = new LinkedHashMap<>();
        facts.forEach(f -> byStrategy.computeIfAbsent(f.strategy(), k -> new ArrayList<>()).add(f));
        List<StrategySlippage> rows = new ArrayList<>();
        byStrategy.forEach((k, list) -> rows.add(new StrategySlippage(k, list.size(), mean(list.stream().map(TradeFact::entrySlippageBps).filter(Objects::nonNull).toList()),
                mean(list.stream().map(TradeFact::exitSlippageBps).filter(Objects::nonNull).toList()))));
        return new SlippageStats(entry, exit, entry.costRupees().add(exit.costRupees()), rows);
    }

    private static SlippageSide side(List<TradeFact> facts, Function<TradeFact, Double> bps, Function<TradeFact, BigDecimal> price) {
        List<TradeFact> with = facts.stream().filter(f -> bps.apply(f) != null).toList();
        List<Double> values = with.stream().map(bps).sorted().toList();
        BigDecimal cost = BigDecimal.ZERO;
        for (TradeFact f : with) {
            cost = cost.add(BigDecimal.valueOf(bps.apply(f)).multiply(price.apply(f)).multiply(BigDecimal.valueOf(f.quantity())).divide(BigDecimal.valueOf(10_000)));
        }
        if (values.isEmpty()) {
            return new SlippageSide(0, null, null, null, null, BigDecimal.ZERO.setScale(2));
        }
        double median = values.size() % 2 == 1 ? values.get(values.size() / 2) : (values.get(values.size() / 2 - 1) + values.get(values.size() / 2)) / 2;
        double p90 = values.get((int) Math.ceil(0.9 * values.size()) - 1);
        return new SlippageSide(values.size(), mean(values), round2(median), round2(p90), round2(values.get(values.size() - 1)), cost.setScale(2, RoundingMode.HALF_UP));
    }

    // ---- adherence

    public static AdherenceStats adherence(List<TradeFact> facts) {
        List<TradeFact> with = facts.stream().filter(f -> f.adherencePct() != null).toList();
        long netFull = with.stream().filter(f -> f.adherencePct() == 100).mapToLong(TradeFact::netPaise).sum();
        long netPartial = with.stream().filter(f -> f.adherencePct() < 100).mapToLong(TradeFact::netPaise).sum();
        Map<String, List<TradeFact>> byStrategy = new LinkedHashMap<>();
        facts.forEach(f -> byStrategy.computeIfAbsent(f.strategy(), k -> new ArrayList<>()).add(f));
        List<StrategyAdherence> rows = new ArrayList<>();
        byStrategy.forEach((k, list) -> rows.add(new StrategyAdherence(k, list.size(),
                mean(list.stream().map(TradeFact::adherencePct).filter(Objects::nonNull).map(Integer::doubleValue).toList()),
                (int) list.stream().filter(f -> Boolean.FALSE.equals(f.setupValid())).count())));
        return new AdherenceStats(facts.size(), with.size(), mean(with.stream().map(f -> f.adherencePct().doubleValue()).toList()),
                (int) with.stream().filter(f -> f.adherencePct() == 100).count(), (int) facts.stream().filter(f -> Boolean.FALSE.equals(f.setupValid())).count(),
                (int) facts.stream().filter(f -> "MANUAL".equals(f.exitReason())).count(), rupees(netFull), rupees(netPartial), rows);
    }

    // ---- counterfactual

    public static Counterfactual counterfactual(List<TradeFact> facts, CounterfactualFilter filter) {
        List<TradeFact> ordered = facts.stream().sorted(Comparator.comparing(TradeFact::closedAt)).toList();
        List<TradeFact> kept = ordered.stream().filter(f -> !filter.excludes(f)).toList();
        List<TradeFact> removed = ordered.stream().filter(filter::excludes).toList();
        Outcome actual = outcome(ordered);
        Outcome simulated = outcome(kept);
        return new Counterfactual(SIMULATED, NOTE, filter, actual, simulated, removed.size(), rupees(removed.stream().mapToLong(TradeFact::netPaise).sum()),
                simulated.netPnl().subtract(actual.netPnl()), simulated.maxDrawdown().subtract(actual.maxDrawdown()));
    }

    /** Trades, winners, net, peak-to-trough drawdown of the cumulative net (from 0), win rate, profit factor (null without losses). */
    static Outcome outcome(List<TradeFact> ordered) {
        long equity = 0;
        long peak = 0;
        long maxDd = 0;
        long wins = 0;
        long losses = 0;
        int winners = 0;
        for (TradeFact f : ordered) {
            equity += f.netPaise();
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
            if (f.netPaise() > 0) {
                wins += f.netPaise();
                winners++;
            } else {
                losses -= f.netPaise();
            }
        }
        Double winRate = ordered.isEmpty() ? null : BigDecimal.valueOf((double) winners / ordered.size()).setScale(4, RoundingMode.HALF_UP).doubleValue();
        Double pf = losses == 0 ? null : BigDecimal.valueOf((double) wins / losses).setScale(4, RoundingMode.HALF_UP).doubleValue();
        return new Outcome(ordered.size(), winners, rupees(equity), rupees(maxDd), winRate, pf);
    }

    private static Double mean(List<Double> values) {
        return values.isEmpty() ? null : round2(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal rupees(long paise) {
        return Money.ofPaise(paise).toRupees();
    }
}
