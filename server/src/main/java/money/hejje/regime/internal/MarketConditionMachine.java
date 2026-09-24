package money.hejje.regime.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import money.hejje.regime.MarketCondition;
import money.hejje.regime.RegimeProperties;

/**
 * The market condition of one session (docs/regime.md, "Market condition"): a state machine over distribution days,
 * rally attempts and follow-through days, run over exactly the last {@code window-sessions} index bars ending at the
 * session. A label is therefore a function of a fixed window of candles up to and including the session: no
 * look-ahead, and the same answer whether it comes from the evening label or from a relabelling job started years
 * earlier. The window opens in {@code CONFIRMED_UPTREND} when the index is at or above its moving average, else in
 * {@code DOWNTREND}; within 200 sessions later events decide the state in practice.
 */
final class MarketConditionMachine {

    /** One index session: {@code volume} is the constituent turnover proxy, NaN when unknown. */
    record Day(LocalDate date, double low, double close, double volume) {
    }

    record DistributionDay(LocalDate date, double close, int index) {
    }

    /** A change that is exactly on a threshold must not miss it to floating-point rounding. */
    private static final double EPSILON = 1e-9;

    private MarketConditionMachine() {
    }

    /** @param days index sessions oldest first, ending at the session to label (history before the window feeds the average) */
    static Labelled<MarketCondition> evaluate(List<Day> days, RegimeProperties.ConditionRules rules) {
        int n = days.size();
        if (n <= rules.sma()) {
            return Labelled.of(MarketCondition.UNKNOWN, "Market condition UNKNOWN: " + n + " index sessions, more than " + rules.sma() + " needed");
        }
        if (Double.isNaN(days.get(n - 1).volume())) {
            return Labelled.of(MarketCondition.UNKNOWN, "Market condition UNKNOWN: no proxy volume for the session (fewer than "
                    + rules.minConstituents() + " constituents with a candle)");
        }
        double[] sma = movingAverage(days, rules.sma());
        int start = Math.max(rules.sma(), n - rules.windowSessions());

        MarketCondition state = days.get(start).close() >= sma[start] ? MarketCondition.CONFIRMED_UPTREND : MarketCondition.DOWNTREND;
        List<DistributionDay> distribution = new ArrayList<>();
        double lowMark = days.get(start).low();
        int rallyDay = 0;
        LocalDate followThrough = null;
        for (int t = start + 1; t < n; t++) {
            Day day = days.get(t);
            Day prev = days.get(t - 1);
            double changePct = (day.close() / prev.close() - 1.0) * 100.0;
            boolean up = day.close() > prev.close();
            boolean higherVolume = !Double.isNaN(day.volume()) && !Double.isNaN(prev.volume()) && day.volume() > prev.volume();
            final int now = t;
            distribution.removeIf(d -> now - d.index() > rules.distributionWindow()
                    || day.close() >= d.close() * (1 + rules.distributionExpiryGainPct() / 100.0));
            if (changePct <= -rules.distributionDropPct() + EPSILON && higherVolume) {
                distribution.add(new DistributionDay(day.date(), day.close(), t));
            }
            switch (state) {
                case CONFIRMED_UPTREND, UPTREND_UNDER_PRESSURE -> {
                    int count = distribution.size();
                    if (count >= rules.downtrendCount() || (day.close() < sma[t] && count >= rules.downtrendBelowSmaCount())) {
                        state = MarketCondition.DOWNTREND;
                        lowMark = day.low();
                        rallyDay = 0;
                    } else if (state == MarketCondition.CONFIRMED_UPTREND && count >= rules.pressureCount()) {
                        state = MarketCondition.UPTREND_UNDER_PRESSURE;
                    } else if (state == MarketCondition.UPTREND_UNDER_PRESSURE && count <= rules.recoverCount()) {
                        state = MarketCondition.CONFIRMED_UPTREND;
                    }
                }
                case DOWNTREND, RALLY_ATTEMPT -> {
                    if (day.low() < lowMark) { // a new low (in a rally attempt: the undercut) resets the count
                        lowMark = day.low();
                        rallyDay = up ? 1 : 0;
                    } else if (rallyDay > 0 || up) {
                        rallyDay++;
                    }
                    if (rallyDay >= rules.followThroughMinDay() && changePct >= rules.followThroughGainPct() - EPSILON && higherVolume) {
                        state = MarketCondition.CONFIRMED_UPTREND;
                        followThrough = day.date();
                        distribution.clear();
                        rallyDay = 0;
                    } else {
                        state = rallyDay > 0 ? MarketCondition.RALLY_ATTEMPT : MarketCondition.DOWNTREND;
                    }
                }
                default -> throw new IllegalStateException(state.name());
            }
        }
        List<String> dates = distribution.stream().map(d -> d.date().toString()).toList();
        String sentence = switch (state) {
            case CONFIRMED_UPTREND -> "Market condition CONFIRMED_UPTREND: " + dates.size() + " distribution days in " + rules.distributionWindow()
                    + " sessions" + (followThrough == null ? "" : ", follow-through day " + followThrough);
            case UPTREND_UNDER_PRESSURE -> "Market condition UPTREND_UNDER_PRESSURE: " + dates.size() + " distribution days in "
                    + rules.distributionWindow() + " sessions " + dates;
            case RALLY_ATTEMPT -> "Market condition RALLY_ATTEMPT: day " + rallyDay + " of the attempt, low to hold " + round2(lowMark);
            case DOWNTREND -> "Market condition DOWNTREND: " + (dates.isEmpty() ? "no rally attempt under way" : dates.size()
                    + " distribution days " + dates + ", no rally attempt under way");
            case UNKNOWN -> throw new IllegalStateException();
        };
        return Labelled.of(state, sentence, "distributionDays", dates, "distributionCount", dates.size(), "rallyDay", rallyDay,
                "followThroughDate", followThrough == null ? null : followThrough.toString(), "indexSma" + rules.sma(), round2(sma[n - 1]));
    }

    private static double[] movingAverage(List<Day> days, int period) {
        double[] out = new double[days.size()];
        double sum = 0;
        for (int i = 0; i < days.size(); i++) {
            sum += days.get(i).close();
            if (i >= period) {
                sum -= days.get(i - period).close();
            }
            out[i] = i >= period - 1 ? sum / period : Double.NaN;
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
