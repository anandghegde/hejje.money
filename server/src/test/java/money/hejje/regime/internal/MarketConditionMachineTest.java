package money.hejje.regime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import money.hejje.regime.MarketCondition;
import money.hejje.regime.RegimeProperties;
import org.junit.jupiter.api.Test;

class MarketConditionMachineTest {

    static final RegimeProperties.ConditionRules RULES = new RegimeProperties.ConditionRules(0.2, 25, 5.0, 4, 1.25, 4, 6, 5, 3, 50, 200, 45);

    /** A scripted index: every step is a close change in percent on higher or lower proxy volume than the day before. */
    static final class Script {
        final List<MarketConditionMachine.Day> days = new ArrayList<>();
        double close = 20_000;
        double volume = 1_000;
        LocalDate date = LocalDate.of(2024, 1, 1);

        Script day(double changePct, boolean higherVolume) {
            return day(changePct, higherVolume, Double.NaN);
        }

        /** @param lowPct the day's low relative to its close in percent (NaN: 0.3 % below the lower of the two closes) */
        Script day(double changePct, boolean higherVolume, double lowPct) {
            double previous = close;
            close *= 1 + changePct / 100.0;
            volume *= higherVolume ? 1.1 : 0.95;
            double low = Double.isNaN(lowPct) ? Math.min(previous, close) * 0.997 : close * (1 + lowPct / 100.0);
            days.add(new MarketConditionMachine.Day(date, low, close, volume));
            date = date.plusDays(1);
            return this;
        }

        Script days(int n, double changePct, boolean higherVolume) {
            for (int i = 0; i < n; i++) {
                day(changePct, higherVolume);
            }
            return this;
        }

        /** Sixty quiet rising sessions: enough for the moving average, index above it, no distribution. */
        static Script uptrend() {
            return new Script().days(60, 0.1, false);
        }

        /** Sixty falling sessions on lower volume: the window opens below the average, in a downtrend, without distribution days. */
        static Script downtrend() {
            return new Script().days(60, -0.3, false);
        }

        Labelled<MarketCondition> label() {
            return MarketConditionMachine.evaluate(days, RULES);
        }
    }

    @Test
    void aQuietUptrendIsConfirmed() {
        Labelled<MarketCondition> l = Script.uptrend().label();
        assertThat(l.label()).isEqualTo(MarketCondition.CONFIRMED_UPTREND);
        assertThat(l.features()).containsEntry("distributionCount", 0);
    }

    @Test
    void aDownCloseNeedsHigherVolumeAndTheThresholdToBeADistributionDay() {
        assertThat(Script.uptrend().day(-0.5, false).label().features()).containsEntry("distributionCount", 0);
        assertThat(Script.uptrend().day(-0.1, true).label().features()).containsEntry("distributionCount", 0);
        assertThat(Script.uptrend().day(-0.2, true).label().features()).containsEntry("distributionCount", 1);
    }

    @Test
    void fourDistributionDaysPutTheUptrendUnderPressureAndSixEndIt() {
        Script s = Script.uptrend();
        for (int i = 0; i < 3; i++) {
            s.day(-0.3, true).day(0.4, false);
        }
        assertThat(s.label().label()).isEqualTo(MarketCondition.CONFIRMED_UPTREND);
        s.day(-0.3, true);
        Labelled<MarketCondition> pressure = s.label();
        assertThat(pressure.label()).isEqualTo(MarketCondition.UPTREND_UNDER_PRESSURE);
        assertThat((List<String>) pressure.features().get("distributionDays")).hasSize(4);
        assertThat(pressure.evidence().get(0)).startsWith("Market condition UPTREND_UNDER_PRESSURE: 4 distribution days in 25 sessions");
        s.day(0.4, false).day(-0.3, true);
        assertThat(s.label().label()).isEqualTo(MarketCondition.UPTREND_UNDER_PRESSURE); // five, still above the average
        s.day(0.4, false).day(-0.3, true);
        assertThat(s.label().label()).isEqualTo(MarketCondition.DOWNTREND);
    }

    @Test
    void fiveDistributionDaysAndACloseBelowTheAverageEndTheUptrend() {
        Script s = Script.uptrend();
        for (int i = 0; i < 4; i++) {
            s.day(-0.3, true).day(0.1, false);
        }
        assertThat(s.label().label()).isEqualTo(MarketCondition.UPTREND_UNDER_PRESSURE);
        s.day(-4.0, true); // the fifth, and far below the 50-session average
        assertThat(s.label().label()).isEqualTo(MarketCondition.DOWNTREND);
    }

    @Test
    void distributionDaysExpireAfterTwentyFiveSessions() {
        Script s = Script.uptrend();
        for (int i = 0; i < 4; i++) {
            s.day(-0.3, true);
        }
        assertThat(s.label().label()).isEqualTo(MarketCondition.UPTREND_UNDER_PRESSURE);
        s.days(22, 0.05, false); // the first distribution day is now 25 sessions back: still counted
        assertThat(s.label().label()).isEqualTo(MarketCondition.UPTREND_UNDER_PRESSURE);
        s.day(0.05, false);      // 26 back: dropped, three left
        Labelled<MarketCondition> l = s.label();
        assertThat(l.label()).isEqualTo(MarketCondition.CONFIRMED_UPTREND);
        assertThat(l.features()).containsEntry("distributionCount", 3);
    }

    @Test
    void aDistributionDayExpiresOnceTheIndexIsFivePercentAboveItsClose() {
        Script s = Script.uptrend();
        for (int i = 0; i < 4; i++) {
            s.day(-0.3, true);
        }
        s.day(2.0, false).day(2.0, false).day(2.0, false);
        Labelled<MarketCondition> l = s.label();
        assertThat(l.features()).containsEntry("distributionCount", 0);
        assertThat(l.label()).isEqualTo(MarketCondition.CONFIRMED_UPTREND);
    }

    @Test
    void theFirstUpCloseAfterANewLowStartsARallyAttemptAndAnUndercutResetsIt() {
        Script s = Script.downtrend();
        assertThat(s.label().label()).isEqualTo(MarketCondition.DOWNTREND);
        s.day(0.5, false);
        Labelled<MarketCondition> one = s.label();
        assertThat(one.label()).isEqualTo(MarketCondition.RALLY_ATTEMPT);
        assertThat(one.features()).containsEntry("rallyDay", 1);
        s.day(-0.1, false).day(0.2, false); // days 2 and 3: a down close that holds the low still counts
        assertThat(s.label().features()).containsEntry("rallyDay", 3);
        s.day(-2.0, false);                 // undercuts the low of the attempt
        Labelled<MarketCondition> reset = s.label();
        assertThat(reset.label()).isEqualTo(MarketCondition.DOWNTREND);
        assertThat(reset.features()).containsEntry("rallyDay", 0);
    }

    @Test
    void aFollowThroughDayFromDayFourConfirmsTheUptrend() {
        Script early = Script.downtrend().day(0.5, false).day(0.1, false).day(1.5, true); // day 3: too early
        assertThat(early.label().label()).isEqualTo(MarketCondition.RALLY_ATTEMPT);

        Script weak = Script.downtrend().day(0.5, false).day(0.1, false).day(0.1, false).day(1.5, false); // day 4 without volume
        assertThat(weak.label().label()).isEqualTo(MarketCondition.RALLY_ATTEMPT);
        Script small = Script.downtrend().day(0.5, false).day(0.1, false).day(0.1, false).day(1.2, true); // day 4 under 1.25 %
        assertThat(small.label().label()).isEqualTo(MarketCondition.RALLY_ATTEMPT);

        Script s = Script.downtrend().day(0.5, false).day(0.1, false).day(0.1, false).day(0.1, false).day(1.3, true); // day 5
        Labelled<MarketCondition> l = s.label();
        assertThat(l.label()).isEqualTo(MarketCondition.CONFIRMED_UPTREND);
        assertThat(l.features()).containsEntry("followThroughDate", s.days.get(s.days.size() - 1).date().toString());
        assertThat(l.evidence().get(0)).contains("follow-through day");
    }

    @Test
    void unknownWithoutProxyVolumeOrHistory() {
        Script s = Script.uptrend();
        MarketConditionMachine.Day last = s.days.remove(s.days.size() - 1);
        s.days.add(new MarketConditionMachine.Day(last.date(), last.low(), last.close(), Double.NaN));
        Labelled<MarketCondition> l = s.label();
        assertThat(l.label()).isEqualTo(MarketCondition.UNKNOWN);
        assertThat(l.evidence().get(0)).contains("fewer than 45 constituents");

        assertThat(new Script().days(50, 0.1, false).label().label()).isEqualTo(MarketCondition.UNKNOWN);
    }

    @Test
    void aDayWithoutVolumeInsideTheWindowIsNeitherDistributionNorFollowThrough() {
        Script s = Script.uptrend().day(-0.5, true);
        MarketConditionMachine.Day d = s.days.remove(s.days.size() - 1);
        s.days.add(new MarketConditionMachine.Day(d.date(), d.low(), d.close(), Double.NaN));
        s.day(0.1, false);
        assertThat(s.label().features()).containsEntry("distributionCount", 0);
    }

    @Test
    void theLabelDependsOnlyOnTheFixedWindowBeforeTheSession() {
        Script s = new Script();
        for (int i = 0; i < 450; i++) { // a long, eventful history
            s.day(i % 7 == 0 ? -0.6 : i % 11 == 0 ? 1.4 : 0.12, i % 7 == 0 || i % 11 == 0);
        }
        List<MarketConditionMachine.Day> tail = s.days.subList(s.days.size() - RULES.windowSessions() - RULES.sma(), s.days.size());
        assertThat(MarketConditionMachine.evaluate(tail, RULES)).isEqualTo(MarketConditionMachine.evaluate(s.days, RULES));
    }
}
