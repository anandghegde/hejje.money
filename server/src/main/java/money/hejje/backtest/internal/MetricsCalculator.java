package money.hejje.backtest.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestMetrics.BucketStats;
import money.hejje.backtest.BacktestMetrics.EquityPoint;
import money.hejje.backtest.BacktestTrade;
import money.hejje.common.Money;

/** PRD section 12.1 metrics from a trade list (docs/backtesting.md, "Metrics"). Pure and order-independent. */
public final class MetricsCalculator {

    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final String[] R_BUCKETS = {"< -2R", "-2R..-1R", "-1R..0", "0..1R", "1R..2R", "2R..3R", "> 3R"};

    private final ZoneId zone;

    public MetricsCalculator(ZoneId zone) {
        this.zone = zone;
    }

    public BacktestMetrics compute(List<BacktestTrade> input, Money initialCapital, List<LocalDate> sessions) {
        List<BacktestTrade> trades = new ArrayList<>(input);
        trades.sort((a, b) -> a.exitTime().compareTo(b.exitTime()));
        int total = trades.size();
        List<BacktestTrade> wins = trades.stream().filter(BacktestTrade::isWin).toList();
        List<BacktestTrade> losses = trades.stream().filter(t -> !t.isWin()).toList();
        long grossPaise = trades.stream().mapToLong(t -> t.grossPnl().paise()).sum();
        long costPaise = trades.stream().mapToLong(t -> t.costs().paise()).sum();
        long netPaise = trades.stream().mapToLong(t -> t.netPnl().paise()).sum();
        long winPaise = wins.stream().mapToLong(t -> t.netPnl().paise()).sum();
        long lossPaise = losses.stream().mapToLong(t -> t.netPnl().paise()).sum();

        Money averageWin = wins.isEmpty() ? Money.ZERO : Money.ofPaise(Math.round((double) winPaise / wins.size()));
        Money averageLoss = losses.isEmpty() ? Money.ZERO : Money.ofPaise(Math.round((double) lossPaise / losses.size()));
        Double winLossRatio = averageLoss.paise() == 0 ? null : (double) averageWin.paise() / Math.abs(averageLoss.paise());
        double expectancyR = total == 0 ? 0 : trades.stream().mapToDouble(BacktestTrade::rMultiple).average().orElse(0);
        Money expectancyMoney = total == 0 ? Money.ZERO : Money.ofPaise(Math.round((double) netPaise / total));
        Double profitFactor = lossPaise == 0 ? null : (double) winPaise / Math.abs(lossPaise);
        double capital = initialCapital.toRupees().doubleValue();
        double totalReturnPct = netPaise / 100.0 / capital * 100.0;

        Double cagr = null;
        if (!sessions.isEmpty()) {
            long days = ChronoUnit.DAYS.between(sessions.get(0), sessions.get(sessions.size() - 1)) + 1;
            if (days >= 365) {
                double growth = (capital + netPaise / 100.0) / capital;
                cagr = growth > 0 ? (Math.pow(growth, 365.0 / days) - 1) * 100.0 : null;
            }
        }

        // daily returns over every session (no trade = 0)
        Map<LocalDate, Long> dailyPnl = new TreeMap<>();
        for (LocalDate s : sessions) {
            dailyPnl.put(s, 0L);
        }
        for (BacktestTrade t : trades) {
            dailyPnl.merge(t.exitTime().atZone(zone).toLocalDate(), t.netPnl().paise(), Long::sum);
        }
        double[] returns = dailyPnl.values().stream().mapToDouble(p -> p / 100.0 / capital).toArray();
        Double sharpe = null;
        Double sortino = null;
        if (returns.length >= 2) {
            double mean = mean(returns);
            double sd = sampleStdDev(returns, mean);
            if (sd > 0) {
                sharpe = mean / sd * Math.sqrt(TRADING_DAYS_PER_YEAR);
            }
            double downside = 0;
            for (double r : returns) {
                downside += Math.min(r, 0) * Math.min(r, 0);
            }
            downside = Math.sqrt(downside / returns.length);
            if (downside > 0) {
                sortino = mean / downside * Math.sqrt(TRADING_DAYS_PER_YEAR);
            }
        }

        // equity, drawdown (money, R) and drawdown duration over the trade sequence
        List<EquityPoint> equity = new ArrayList<>();
        List<EquityPoint> drawdown = new ArrayList<>();
        long cum = 0;
        long peak = 0;
        long maxDd = 0;
        double cumR = 0;
        double peakR = 0;
        double maxDdR = 0;
        LocalDate peakDate = sessions.isEmpty() ? null : sessions.get(0);
        long maxDdDays = 0;
        boolean inDrawdown = false;
        for (BacktestTrade t : trades) {
            cum += t.netPnl().paise();
            cumR += t.rMultiple();
            LocalDate date = t.exitTime().atZone(zone).toLocalDate();
            if (cum >= peak) {
                if (inDrawdown && peakDate != null) {
                    maxDdDays = Math.max(maxDdDays, ChronoUnit.DAYS.between(peakDate, date)); // recovered today
                }
                peak = cum;
                peakDate = date;
                inDrawdown = false;
            } else {
                inDrawdown = true;
                if (peakDate != null) {
                    maxDdDays = Math.max(maxDdDays, ChronoUnit.DAYS.between(peakDate, date));
                }
            }
            peakR = Math.max(peakR, cumR);
            maxDd = Math.max(maxDd, peak - cum);
            maxDdR = Math.max(maxDdR, peakR - cumR);
            equity.add(new EquityPoint(t.exitTime(), initialCapital.plus(Money.ofPaise(cum))));
            drawdown.add(new EquityPoint(t.exitTime(), Money.ofPaise(cum - peak)));
        }
        if (peakDate != null && !sessions.isEmpty() && cum < peak) {
            maxDdDays = Math.max(maxDdDays, ChronoUnit.DAYS.between(peakDate, sessions.get(sessions.size() - 1)));
        }

        int consecutiveWins = 0;
        int consecutiveLosses = 0;
        int runWins = 0;
        int runLosses = 0;
        for (BacktestTrade t : trades) {
            if (t.isWin()) {
                runWins++;
                runLosses = 0;
            } else {
                runLosses++;
                runWins = 0;
            }
            consecutiveWins = Math.max(consecutiveWins, runWins);
            consecutiveLosses = Math.max(consecutiveLosses, runLosses);
        }
        double avgHolding = total == 0 ? 0 : trades.stream().mapToLong(BacktestTrade::holdingMinutes).average().orElse(0);
        Money largestWin = wins.stream().map(BacktestTrade::netPnl).max((a, b) -> Long.compare(a.paise(), b.paise())).orElse(Money.ZERO);
        Money largestLoss = losses.stream().map(BacktestTrade::netPnl).min((a, b) -> Long.compare(a.paise(), b.paise())).orElse(Money.ZERO);

        Map<String, Integer> rDistribution = new LinkedHashMap<>();
        for (String bucket : R_BUCKETS) {
            rDistribution.put(bucket, 0);
        }
        for (BacktestTrade t : trades) {
            rDistribution.merge(rBucket(t.rMultiple()), 1, Integer::sum);
        }

        return new BacktestMetrics(total, wins.size(), losses.size(), rate(wins.size(), total), rate(losses.size(), total), averageWin,
                averageLoss, winLossRatio, expectancyR, expectancyMoney, profitFactor, totalReturnPct, cagr, sharpe, sortino,
                Money.ofPaise(maxDd), maxDdR, maxDd / 100.0 / capital * 100.0, (int) maxDdDays, consecutiveWins, consecutiveLosses, avgHolding,
                largestWin, largestLoss, Money.ofPaise(grossPaise), Money.ofPaise(costPaise), Money.ofPaise(netPaise), sessions.size(),
                rDistribution,
                buckets(trades, t -> t.entryTime().atZone(zone).toLocalDate().toString().substring(0, 7)),
                buckets(trades, t -> t.entryTime().atZone(zone).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT)),
                buckets(trades, t -> String.format("%02d", t.entryTime().atZone(zone).getHour())),
                equity, drawdown);
    }

    private static Map<String, BucketStats> buckets(List<BacktestTrade> trades, Function<BacktestTrade, String> key) {
        Map<String, List<BacktestTrade>> grouped = new TreeMap<>();
        for (BacktestTrade t : trades) {
            grouped.computeIfAbsent(key.apply(t), k -> new ArrayList<>()).add(t);
        }
        Map<String, BucketStats> out = new LinkedHashMap<>();
        grouped.forEach((k, list) -> {
            long net = list.stream().mapToLong(t -> t.netPnl().paise()).sum();
            long wins = list.stream().filter(BacktestTrade::isWin).count();
            out.put(k, new BucketStats(list.size(), Money.ofPaise(net), rate((int) wins, list.size())));
        });
        return out;
    }

    private static String rBucket(double r) {
        if (r < -2) {
            return R_BUCKETS[0];
        }
        if (r < -1) {
            return R_BUCKETS[1];
        }
        if (r < 0) {
            return R_BUCKETS[2];
        }
        if (r < 1) {
            return R_BUCKETS[3];
        }
        if (r < 2) {
            return R_BUCKETS[4];
        }
        if (r < 3) {
            return R_BUCKETS[5];
        }
        return R_BUCKETS[6];
    }

    private static double rate(int part, int total) {
        return total == 0 ? 0 : (double) part / total;
    }

    static double mean(double[] values) {
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return s / values.length;
    }

    static double sampleStdDev(double[] values, double mean) {
        if (values.length < 2) {
            return 0;
        }
        double acc = 0;
        for (double v : values) {
            acc += (v - mean) * (v - mean);
        }
        return Math.sqrt(acc / (values.length - 1));
    }

    static DayOfWeek dayOf(LocalDate d) {
        return d.getDayOfWeek();
    }
}
