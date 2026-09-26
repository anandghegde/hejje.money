package money.hejje.ratings.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.ratings.DailyRating;
import money.hejje.ratings.GroupRank;
import money.hejje.ratings.RatingsProperties;

/**
 * The ratings of one session for the whole universe (docs/ratings.md). Pure: a function of each member's candles up to
 * and including the session (later candles in the series are never read), the formula and the engine version. Members
 * are processed in instrument-id order so the output is reproducible.
 */
final class RatingsEngine {

    static final String[] GRADES = {"A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D+", "D", "D-", "E"};

    /** A universe member: its industry (null when the file has none) and its D1 history. */
    record Member(UUID id, String symbol, String industry, DailySeries series, BigDecimal tick) {

        Member(UUID id, String symbol, String industry, DailySeries series) {
            this(id, symbol, industry, series, new BigDecimal("0.05"));
        }
    }

    record Result(List<DailyRating> ratings, List<GroupRank> groups) {
    }

    private final RatingsProperties.Formula f;
    private final String version;

    RatingsEngine(RatingsProperties.Formula formula, String version) {
        this.f = formula;
        this.version = version;
    }

    Result compute(LocalDate date, List<Member> universe) {
        List<Member> members = universe.stream().filter(m -> m.series().indexOf(date) >= 0).sorted(Comparator.comparing(Member::id)).toList();
        int n = members.size();
        double[] rs = new double[n];
        double[] ad = new double[n];
        double[] nearHigh = new double[n];
        int[] horizons = new int[n];
        for (int k = 0; k < n; k++) {
            DailySeries s = members.get(k).series();
            int i = s.indexOf(date);
            horizons[k] = Math.min(f.rsWeights().size(), i / f.quarterSessions());
            rs[k] = rsRaw(s, i, horizons[k]);
            ad[k] = adRaw(s, i);
            nearHigh[k] = -offHigh(s, i);
        }
        double[] rsPct = percentiles(rs);
        double[] adPct = percentiles(ad);
        double[] highPct = percentiles(nearHigh);

        // groups: median RS raw of the rated members, ranked 1..N
        Map<String, List<Double>> byGroup = new TreeMap<>();
        Map<String, String> groupName = new TreeMap<>();
        for (int k = 0; k < n; k++) {
            String industry = members.get(k).industry();
            if (industry != null && !Double.isNaN(rs[k])) {
                byGroup.computeIfAbsent(groupId(industry), g -> new ArrayList<>()).add(rs[k]);
                groupName.put(groupId(industry), industry);
            }
        }
        List<Map.Entry<String, Double>> strengths = new ArrayList<>();
        byGroup.forEach((g, values) -> {
            if (values.size() >= f.minGroupMembers()) {
                strengths.add(Map.entry(g, median(values)));
            }
        });
        strengths.sort(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> rankOf = new LinkedHashMap<>();
        List<GroupRank> groups = new ArrayList<>();
        for (int r = 0; r < strengths.size(); r++) {
            String g = strengths.get(r).getKey();
            rankOf.put(g, r + 1);
            groups.add(new GroupRank(date, g, version, groupName.get(g), r + 1, round6(strengths.get(r).getValue()), byGroup.get(g).size()));
        }

        // technical composite: percentile of the weighted component percentiles
        RatingsProperties.CompositeWeights w = f.compositeWeights();
        double[] score = new double[n];
        double[] groupPct = new double[n];
        for (int k = 0; k < n; k++) {
            String industry = members.get(k).industry();
            Integer rank = industry == null ? null : rankOf.get(groupId(industry));
            groupPct[k] = rank == null ? Double.NaN : rankOf.size() == 1 ? 0.5 : 1.0 - (rank - 1.0) / (rankOf.size() - 1.0);
            score[k] = Double.isNaN(rsPct[k]) || Double.isNaN(adPct[k]) ? Double.NaN
                    : w.rs() * rsPct[k] + w.ad() * adPct[k] + w.group() * (Double.isNaN(groupPct[k]) ? 0.5 : groupPct[k]) + w.offHigh() * highPct[k];
        }
        double[] compositePct = percentiles(score);

        List<DailyRating> ratings = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            Member m = members.get(k);
            DailySeries s = m.series();
            int i = s.indexOf(date);
            String industry = m.industry();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("sessions", i + 1);
            evidence.put("rsQuarters", horizons[k]);
            evidence.put("partial", horizons[k] < f.rsWeights().size());
            evidence.put("universe", n);
            if (!Double.isNaN(score[k])) {
                evidence.put("rsPct", round6(rsPct[k]));
                evidence.put("adPct", round6(adPct[k]));
                evidence.put("groupPct", Double.isNaN(groupPct[k]) ? null : round6(groupPct[k]));
                evidence.put("offHighPct", round6(highPct[k]));
            }
            ratings.add(new DailyRating(date, m.id(), version, m.symbol(), boxed(rs[k]), rating(rsPct[k]), boxed(ad[k]), grade(adPct[k]),
                    round6(offHigh(s, i)), round6(offLow(s, i)), boxed(volumeVsAverage(s, i)), boxed(upDownVolume(s, i)), boxed(turnoverCr(s, i)),
                    BigDecimal.valueOf(s.close()[i]).setScale(2, RoundingMode.HALF_UP),
                    i == 0 || s.close()[i - 1] == 0 ? null : round6((s.close()[i] / s.close()[i - 1] - 1.0) * 100.0),
                    industry == null ? null : groupId(industry), industry == null ? null : rankOf.get(groupId(industry)),
                    rating(compositePct[k]), evidence, null));
        }
        return new Result(ratings, groups);
    }

    /** Weighted return over the quarters the listing has; NaN below one quarter of history. */
    private double rsRaw(DailySeries s, int i, int horizons) {
        if (horizons == 0) {
            return Double.NaN;
        }
        double sum = 0;
        double weights = 0;
        for (int h = 1; h <= horizons; h++) {
            double then = s.close()[i - h * f.quarterSessions()];
            if (then <= 0) {
                return Double.NaN;
            }
            sum += f.rsWeights().get(h - 1) * (s.close()[i] / then - 1.0);
            weights += f.rsWeights().get(h - 1);
        }
        return sum / weights;
    }

    /** Sum(clv x volume) / Sum(volume); clv = ((close - low) - (high - close)) / (high - low), 0 on a zero range. */
    private double adRaw(DailySeries s, int i) {
        if (i + 1 < f.adSessions()) {
            return Double.NaN;
        }
        double flow = 0;
        double volume = 0;
        for (int j = i - f.adSessions() + 1; j <= i; j++) {
            double range = s.high()[j] - s.low()[j];
            double clv = range <= 0 ? 0 : ((s.close()[j] - s.low()[j]) - (s.high()[j] - s.close()[j])) / range;
            flow += clv * s.volume()[j];
            volume += s.volume()[j];
        }
        return volume <= 0 ? Double.NaN : flow / volume;
    }

    private double offHigh(DailySeries s, int i) {
        double high = 0;
        for (int j = Math.max(0, i - f.highLowSessions() + 1); j <= i; j++) {
            high = Math.max(high, s.high()[j]);
        }
        return high <= 0 ? 0 : (high - s.close()[i]) / high * 100.0;
    }

    private double offLow(DailySeries s, int i) {
        double low = Double.MAX_VALUE;
        for (int j = Math.max(0, i - f.highLowSessions() + 1); j <= i; j++) {
            low = Math.min(low, s.low()[j]);
        }
        return low <= 0 ? 0 : (s.close()[i] - low) / low * 100.0;
    }

    /** The session's volume against the mean of the previous window (the session itself excluded). */
    private double volumeVsAverage(DailySeries s, int i) {
        if (i < f.volumeSessions()) {
            return Double.NaN;
        }
        double sum = 0;
        for (int j = i - f.volumeSessions(); j < i; j++) {
            sum += s.volume()[j];
        }
        return sum <= 0 ? Double.NaN : (s.volume()[i] / (sum / f.volumeSessions()) - 1.0) * 100.0;
    }

    private double upDownVolume(DailySeries s, int i) {
        if (i < f.volumeSessions()) {
            return Double.NaN;
        }
        double up = 0;
        double down = 0;
        for (int j = i - f.volumeSessions() + 1; j <= i; j++) {
            if (s.close()[j] > s.close()[j - 1]) {
                up += s.volume()[j];
            } else if (s.close()[j] < s.close()[j - 1]) {
                down += s.volume()[j];
            }
        }
        return down <= 0 ? Double.NaN : up / down;
    }

    private double turnoverCr(DailySeries s, int i) {
        int from = Math.max(0, i - f.volumeSessions() + 1);
        double sum = 0;
        for (int j = from; j <= i; j++) {
            sum += s.close()[j] * s.volume()[j];
        }
        return sum / (i - from + 1) / 1e7;
    }

    /** Share of the other rated values that are strictly lower, 0..1; NaN stays NaN; a single value is 0.5. */
    static double[] percentiles(double[] values) {
        double[] sorted = Arrays.stream(values).filter(v -> !Double.isNaN(v)).sorted().toArray();
        double[] out = new double[values.length];
        for (int k = 0; k < values.length; k++) {
            if (Double.isNaN(values[k])) {
                out[k] = Double.NaN;
            } else if (sorted.length == 1) {
                out[k] = 0.5;
            } else {
                int lower = lowerBound(sorted, values[k]);
                out[k] = (double) lower / (sorted.length - 1);
            }
        }
        return out;
    }

    private static int lowerBound(double[] sorted, double v) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < v) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** 1-99 from a 0..1 percentile. */
    static Integer rating(double pct) {
        return Double.isNaN(pct) ? null : (int) Math.round(1 + 98 * pct);
    }

    /** Thirteen equal-width bands of the percentile, best first. */
    static String grade(double pct) {
        return Double.isNaN(pct) ? null : GRADES[Math.min(GRADES.length - 1, (int) ((1.0 - pct) * GRADES.length))];
    }

    static String groupId(String industry) {
        return industry.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static double median(List<Double> values) {
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2.0;
    }

    private static Double boxed(double v) {
        return Double.isNaN(v) ? null : round6(v);
    }

    static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
