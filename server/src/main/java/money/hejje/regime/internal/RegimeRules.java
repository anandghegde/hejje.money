package money.hejje.regime.internal;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.indicators.Bar;
import money.hejje.regime.Breadth;
import money.hejje.regime.IntradayStructure;
import money.hejje.regime.Opening;
import money.hejje.regime.RegimeProperties;
import money.hejje.regime.Trend;
import money.hejje.regime.Volatility;

/** The PRD section 13 rules, one static function per dimension (docs/regime.md). Pure: inputs in, label + evidence out. */
final class RegimeRules {

    private RegimeRules() {
    }

    // --- trend ---

    static Labelled<Trend> trend(DailyState s, RegimeProperties.TrendRules r) {
        if (s == null || Double.isNaN(s.emaSlow()) || Double.isNaN(s.adx()) || Double.isNaN(s.emaFastBack())) {
            int have = s == null ? 0 : s.sessions();
            return Labelled.of(Trend.UNKNOWN, "Trend: not enough daily bars (" + have + "; EMA" + r.emaSlow() + " and ADX" + r.adxPeriod()
                    + " need about " + Math.max(r.emaSlow(), 2 * r.adxPeriod() + r.slopeSessions()) + ")", "dailyBars", have);
        }
        double slopePct = 100.0 * (s.emaFast() - s.emaFastBack()) / s.emaFastBack();
        boolean bull = s.close() > s.emaFast() && s.emaFast() > s.emaSlow();
        boolean bear = s.close() < s.emaFast() && s.emaFast() < s.emaSlow();
        Trend label;
        if (bull && s.adx() >= r.trendAdx()) {
            label = s.adx() >= r.strongAdx() && slopePct >= r.strongSlopePct() ? Trend.STRONG_UP : Trend.UP;
        } else if (bear && s.adx() >= r.trendAdx()) {
            label = s.adx() >= r.strongAdx() && slopePct <= -r.strongSlopePct() ? Trend.STRONG_DOWN : Trend.DOWN;
        } else {
            label = Trend.RANGE;
        }
        String structure = bull ? "close above rising EMAs" : bear ? "close below falling EMAs" : "EMAs mixed";
        String evidence = String.format(Locale.ROOT, "Trend %s: close %.2f vs EMA%d %.2f / EMA%d %.2f (%s), EMA%d slope %+.2f%% over %d sessions, ADX %.1f",
                label, s.close(), r.emaFast(), s.emaFast(), r.emaSlow(), s.emaSlow(), structure, r.emaFast(), slopePct, r.slopeSessions(), s.adx());
        return Labelled.of(label, evidence, "close", round(s.close(), 2), "emaFast", round(s.emaFast(), 2), "emaSlow", round(s.emaSlow(), 2),
                "emaSlopePct", round(slopePct, 3), "adx", round(s.adx(), 2));
    }

    // --- volatility ---

    static Labelled<Volatility> volatility(DailyState s, RegimeProperties.VolatilityRules r, int lookback) {
        if (s == null || (s.vixPercentile() == null && s.atrRatioPercentile() == null)) {
            int have = s == null ? 0 : s.percentileWindow();
            return Labelled.of(Volatility.UNKNOWN, "Volatility: not enough history for percentiles (" + have + " sessions in the window)",
                    "percentileWindow", have);
        }
        double combined = s.vixPercentile() != null && s.atrRatioPercentile() != null ? (s.vixPercentile() + s.atrRatioPercentile()) / 2
                : s.vixPercentile() != null ? s.vixPercentile() : s.atrRatioPercentile();
        Volatility label = combined < r.veryLowPct() ? Volatility.VERY_LOW : combined < r.lowPct() ? Volatility.LOW
                : combined < r.highPct() ? Volatility.NORMAL : combined < r.extremePct() ? Volatility.HIGH : Volatility.EXTREME;
        StringBuilder e = new StringBuilder("Volatility ").append(label).append(": ");
        if (s.vixPercentile() != null) {
            e.append(String.format(Locale.ROOT, "VIX %.2f at the %.0fth percentile", s.vix(), s.vixPercentile()));
        } else {
            e.append("VIX unavailable");
        }
        if (s.atrRatioPercentile() != null) {
            e.append(String.format(Locale.ROOT, ", ATR%d/close at the %.0fth percentile", r.atrPeriod(), s.atrRatioPercentile()));
        } else {
            e.append(", ATR percentile unavailable");
        }
        e.append(String.format(Locale.ROOT, " (combined %.0f over %d sessions)", combined, s.percentileWindow()));
        return Labelled.of(label, e.toString(), "vix", round(s.vix(), 2), "vixPercentile", s.vixPercentile() == null ? null : round(s.vixPercentile(), 1),
                "atrRatioPercentile", s.atrRatioPercentile() == null ? null : round(s.atrRatioPercentile(), 1), "volatilityPercentile", round(combined, 1),
                "percentileWindow", s.percentileWindow(), "dailyAtr", round(s.atr(), 2));
    }

    // --- opening ---

    static Labelled<Opening> opening(DailyState s, IntradayInput in, RegimeProperties.OpeningRules r) {
        double prevClose = s == null ? Double.NaN : s.prevClose();
        double open = !in.bars().isEmpty() ? in.bars().get(0).open() : s == null ? Double.NaN : s.open();
        if (Double.isNaN(prevClose) || Double.isNaN(open) || prevClose <= 0) {
            return Labelled.of(Opening.UNKNOWN, "Opening: previous close or today's open unavailable");
        }
        double gapPct = 100.0 * (open - prevClose) / prevClose;
        Opening label = Math.abs(gapPct) < r.flatGapPct() ? Opening.FLAT : gapPct > 0 ? Opening.GAP_UP : Opening.GAP_DOWN;
        String evidence = String.format(Locale.ROOT, "Opening %s: open %.2f vs previous close %.2f (gap %+.2f%%)", label, open, prevClose, gapPct);
        Double close15 = null;
        if (label != Opening.FLAT) {
            LocalTime rangeEnd = HejjeClock.SESSION_OPEN.plusMinutes(r.openingRangeMinutes());
            Bar last = null;
            for (Bar b : in.bars()) {
                if (!b.closeTimeOfDay().isAfter(rangeEnd)) {
                    last = b;
                }
            }
            if (last != null && last.closeTimeOfDay().equals(rangeEnd)) {
                close15 = last.close();
                double gap = open - prevClose;
                boolean continued = gapPct > 0 ? close15 > open : close15 < open;
                boolean rejected = gapPct > 0 ? close15 <= open - r.rejectionFraction() * gap : close15 >= open - r.rejectionFraction() * gap;
                if (continued) {
                    label = Opening.GAP_CONTINUATION;
                } else if (rejected) {
                    label = Opening.GAP_REJECTION;
                }
                evidence = String.format(Locale.ROOT, "Opening %s: open %.2f vs previous close %.2f (gap %+.2f%%), %d-minute close %.2f", label, open,
                        prevClose, gapPct, r.openingRangeMinutes(), close15);
            }
        }
        return Labelled.of(label, evidence, "open", round(open, 2), "prevClose", round(prevClose, 2), "gapPct", round(gapPct, 3),
                "openingRangeClose", close15 == null ? null : round(close15, 2));
    }

    // --- breadth ---

    static Labelled<Breadth> breadth(BreadthInput in, RegimeProperties.BreadthRules r) {
        int total = in.universeSize();
        double coverage = total == 0 ? 0 : (double) in.constituents().size() / total;
        if (total == 0 || coverage < r.minCoverage()) {
            return Labelled.of(Breadth.UNKNOWN, String.format(Locale.ROOT, "Breadth: quotes for %d of %d constituents (need %.0f%%)", in.constituents().size(),
                    total, 100 * r.minCoverage()), "breadthCoverage", round(coverage, 2));
        }
        int advances = 0;
        int declines = 0;
        int withVwap = 0;
        int aboveVwap = 0;
        for (BreadthInput.Constituent c : in.constituents()) {
            if (c.last() > c.prevClose()) {
                advances++;
            } else if (c.last() < c.prevClose()) {
                declines++;
            }
            if (c.vwap() != null) {
                withVwap++;
                if (c.last() > c.vwap()) {
                    aboveVwap++;
                }
            }
        }
        double adRatio = advances + declines == 0 ? Double.NaN : (double) advances / (advances + declines);
        double vwapRatio = withVwap == 0 ? Double.NaN : (double) aboveVwap / withVwap;
        double composite = !Double.isNaN(adRatio) && !Double.isNaN(vwapRatio) ? (adRatio + vwapRatio) / 2 : !Double.isNaN(adRatio) ? adRatio : vwapRatio;
        if (Double.isNaN(composite)) {
            return Labelled.of(Breadth.UNKNOWN, "Breadth: no constituent moved yet", "breadthCoverage", round(coverage, 2));
        }
        Breadth label = composite >= r.strongPositive() ? Breadth.STRONG_POSITIVE : composite >= r.positive() ? Breadth.POSITIVE
                : composite <= r.strongNegative() ? Breadth.STRONG_NEGATIVE : composite <= r.negative() ? Breadth.NEGATIVE : Breadth.MIXED;
        String evidence = String.format(Locale.ROOT, "Breadth %s: %d advances / %d declines", label, advances, declines)
                + (withVwap == 0 ? "" : String.format(Locale.ROOT, ", %d of %d above VWAP", aboveVwap, withVwap))
                + String.format(Locale.ROOT, " (%.0f%% positive, %d of %d constituents)", 100 * composite, in.constituents().size(), total);
        return Labelled.of(label, evidence, "advances", advances, "declines", declines, "aboveVwap", withVwap == 0 ? null : aboveVwap,
                "withVwap", withVwap == 0 ? null : withVwap, "breadthRatio", round(composite, 3), "breadthCoverage", round(coverage, 2));
    }

    // --- intraday structure ---

    static Labelled<IntradayStructure> structure(IntradayInput in, double dailyAtr, RegimeProperties.StructureRules r) {
        List<Bar> bars = in.bars();
        if (bars.size() < r.minBars()) {
            return Labelled.of(IntradayStructure.UNKNOWN, "Intraday structure: " + bars.size() + " bars so far (need " + r.minBars() + ")", "intradayBars", bars.size());
        }
        LocalTime rangeEnd = HejjeClock.SESSION_OPEN.plusMinutes(r.openingRangeMinutes());
        double open = bars.get(0).open();
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        double orHigh = Double.NEGATIVE_INFINITY;
        double orLow = Double.POSITIVE_INFINITY;
        boolean anyVolume = bars.stream().anyMatch(b -> b.volume() > 0);
        double sumPv = 0;
        double sumV = 0;
        int crosses = 0;
        int lastSign = 0;
        for (Bar b : bars) {
            high = Math.max(high, b.high());
            low = Math.min(low, b.low());
            if (!b.closeTimeOfDay().isAfter(rangeEnd)) {
                orHigh = Math.max(orHigh, b.high());
                orLow = Math.min(orLow, b.low());
            }
            double w = anyVolume ? b.volume() : 1;
            sumPv += b.typicalPrice() * w;
            sumV += w;
            if (sumV > 0) {
                double avg = sumPv / sumV;
                int sign = b.close() > avg ? 1 : b.close() < avg ? -1 : 0;
                if (sign != 0) {
                    if (lastSign != 0 && sign != lastSign) {
                        crosses++;
                    }
                    lastSign = sign;
                }
            }
        }
        double close = bars.get(bars.size() - 1).close();
        double range = high - low;
        double orRange = orHigh - orLow;
        double expansion = orRange > 0 ? range / orRange : Double.NaN;
        double closePos = range > 0 ? (close - low) / range : Double.NaN;
        double rangeAtr = !Double.isNaN(dailyAtr) && dailyAtr > 0 ? range / dailyAtr : Double.NaN;
        double upExcursion = range > 0 ? (high - open) / range : Double.NaN;
        double downExcursion = range > 0 ? (open - low) / range : Double.NaN;
        IntradayStructure label;
        String why;
        if (!Double.isNaN(rangeAtr) && rangeAtr <= r.compressionMaxRangeAtr()) {
            label = IntradayStructure.LOW_VOLATILITY_COMPRESSION;
            why = String.format(Locale.ROOT, "day range %.2f is %.2fx the daily ATR (≤ %.2f)", range, rangeAtr, r.compressionMaxRangeAtr());
        } else if ((Double.isNaN(rangeAtr) || rangeAtr >= r.reversalMinRangeAtr()) && range > 0
                && ((close < open && upExcursion >= r.reversalMinExcursion()) || (close > open && downExcursion >= r.reversalMinExcursion()))) {
            label = IntradayStructure.REVERSAL_DAY;
            why = String.format(Locale.ROOT, "%.0f%% of the day range was spent %s the open before closing %s it", 100 * (close < open ? upExcursion : downExcursion),
                    close < open ? "above" : "below", close < open ? "below" : "above");
        } else if (!Double.isNaN(expansion) && expansion >= r.trendRangeExpansion() && !Double.isNaN(closePos)
                && (closePos >= r.trendClosePosition() || closePos <= 1 - r.trendClosePosition()) && crosses <= r.trendMaxVwapCrosses()) {
            label = IntradayStructure.TREND_DAY;
            why = String.format(Locale.ROOT, "range %.1fx the opening range, close at %.0f%% of the day range, %d VWAP crosses", expansion, 100 * closePos, crosses);
        } else if (!Double.isNaN(rangeAtr) && rangeAtr >= r.chopMinRangeAtr() && crosses >= r.chopMinVwapCrosses()) {
            label = IntradayStructure.HIGH_VOLATILITY_CHOP;
            why = String.format(Locale.ROOT, "%d VWAP crosses with a range %.2fx the daily ATR", crosses, rangeAtr);
        } else {
            label = IntradayStructure.RANGE_DAY;
            why = String.format(Locale.ROOT, "range %s the opening range, close at %s of the day range, %d VWAP crosses",
                    Double.isNaN(expansion) ? "n/a vs" : String.format(Locale.ROOT, "%.1fx", expansion),
                    Double.isNaN(closePos) ? "n/a" : String.format(Locale.ROOT, "%.0f%%", 100 * closePos), crosses);
        }
        String evidence = "Intraday structure " + label + (in.sessionClosed() ? "" : " (progressive, " + bars.size() + " bars)") + ": " + why;
        return Labelled.of(label, evidence, "intradayBars", bars.size(), "dayHigh", round(high, 2), "dayLow", round(low, 2), "dayClose", round(close, 2),
                "rangeExpansion", round(expansion, 2), "closePosition", round(closePos, 2), "rangeAtr", round(rangeAtr, 2), "vwapCrosses", crosses);
    }

    static Double round(double v, int places) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        double p = Math.pow(10, places);
        return Math.round(v * p) / p;
    }
}
