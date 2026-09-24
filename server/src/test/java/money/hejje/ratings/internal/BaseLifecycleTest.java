package money.hejje.ratings.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import money.hejje.ratings.Base;
import money.hejje.ratings.BaseStatus;
import money.hejje.ratings.BaseType;
import org.junit.jupiter.api.Test;

class BaseLifecycleTest {

    /** Everything the walk of a fixture produced: {@code TYPE STATUS} per event, and the bases in their final state. */
    record Walk(List<String> events, List<Base> detected, List<Base> last) {

        /** Events of one base type (the zigzag of the flat base fixtures also throws off moving-average reversal setups). */
        List<String> eventsOf(BaseType type) {
            return events.stream().filter(e -> e.startsWith(type + " ")).toList();
        }

        Base first(BaseType type) {
            return detected.stream().filter(b -> b.type() == type).findFirst().orElseThrow();
        }

        Base finalOf(BaseType type) {
            return last.stream().filter(b -> b.type() == type).reduce((a, b) -> b).orElseThrow();
        }
    }

    static Walk walk(String fixture, int from, int to, List<Base> existing) {
        RatingsEngine.Member m = BaseFixtures.member(fixture);
        List<String> events = new ArrayList<>();
        List<Base> detected = new ArrayList<>();
        List<Base> last = new ArrayList<>(existing);
        new BaseWalker(BaseFixtures.CFG, "1").walk(m, from, to < 0 ? m.series().size() - 1 : to, existing, new BaseWalker.Sink() {
            @Override
            public void detected(Base base) {
                events.add(base.type() + " " + base.status());
                detected.add(base);
                last.add(base);
            }

            @Override
            public void status(Base base) {
                events.add(base.type() + " " + base.status());
                last.replaceAll(b -> b.id().equals(base.id()) ? base : b);
            }
        });
        return new Walk(events, detected, last);
    }

    @Test
    void thePlanIsTickRoundedAroundThePivot() {
        Base b = walk("flat_base", 0, -1, List.of()).first(BaseType.FLAT_BASE);
        assertThat(b.type()).isEqualTo(BaseType.FLAT_BASE);
        assertThat(b.pivot()).isEqualByComparingTo("150.75");
        assertThat(b.buyLow()).isEqualByComparingTo("150.75");
        assertThat(b.buyHigh()).isEqualByComparingTo("158.30"); // 158.2875 to the 0.05 tick
        assertThat(b.stop()).isEqualByComparingTo("140.20");    // 140.1975
        assertThat(b.goal()).isEqualByComparingTo("180.90");
        assertThat(b.status()).isEqualTo(BaseStatus.FORMING);   // 138 is more than 5 % under the pivot
    }

    @Test
    void aBreakoutOnVolumeRunsToTheGoal() {
        Walk w = walk("lifecycle_goal", 0, -1, List.of());
        assertThat(w.eventsOf(BaseType.FLAT_BASE)).containsExactly("FLAT_BASE FORMING", "FLAT_BASE NEAR_PIVOT", "FLAT_BASE IN_BUY_ZONE", "FLAT_BASE EXTENDED",
                "FLAT_BASE HIT_GOAL");
        Base b = w.finalOf(BaseType.FLAT_BASE);
        assertThat(b.volumeConfirmed()).isTrue();            // 200000 against a 100000 average
        assertThat(b.entry()).isEqualByComparingTo("150.75"); // opened below the pivot: bought at the pivot
        assertThat(b.exit()).isEqualByComparingTo("180.90");
        assertThat(b.outcomePct()).isEqualTo(20.0);
        assertThat(b.outcomeR()).isEqualTo(2.86);             // 30.15 / 10.55
    }

    @Test
    void aBreakoutThatFallsBackIsStoppedForMinusOneR() {
        Walk w = walk("lifecycle_stopped", 0, -1, List.of());
        assertThat(w.eventsOf(BaseType.FLAT_BASE)).containsExactly("FLAT_BASE FORMING", "FLAT_BASE NEAR_PIVOT", "FLAT_BASE IN_BUY_ZONE", "FLAT_BASE PULLBACK",
                "FLAT_BASE STOPPED");
        Base b = w.finalOf(BaseType.FLAT_BASE);
        assertThat(b.volumeConfirmed()).isFalse();
        assertThat(b.exit()).isEqualByComparingTo("140.20");
        assertThat(b.outcomeR()).isEqualTo(-1.0);
    }

    @Test
    void aBaseThatBreaksDownBeforeTriggeringFailsWithoutAnOutcome() {
        Base b = walk("lifecycle_failed", 0, -1, List.of()).finalOf(BaseType.FLAT_BASE);
        assertThat(b.status()).isEqualTo(BaseStatus.FAILED);
        assertThat(b.triggerDate()).isNull();
        assertThat(b.outcomeR()).isNull();
    }

    @Test
    void anUntriggeredBaseExpires() {
        // the flat base's zigzag simply continues: never through the pivot, never under the base low
        RatingsEngine.Member m = BaseFixtures.member("flat_base");
        DailySeries s = m.series();
        int n = s.size();
        int extra = 70;
        DailySeries longer = new DailySeries(new long[n + extra], new double[n + extra], new double[n + extra], new double[n + extra],
                new double[n + extra], new double[n + extra]);
        for (int i = 0; i < n + extra; i++) {
            int src = i < n ? i : n - 10 + (i - n) % 10;
            longer.day()[i] = s.day()[0] + i; // consecutive days are fine here
            longer.open()[i] = s.open()[src];
            longer.high()[i] = s.high()[src];
            longer.low()[i] = s.low()[src];
            longer.close()[i] = s.close()[src];
            longer.volume()[i] = s.volume()[src];
        }
        List<Base> last = new ArrayList<>();
        new BaseWalker(BaseFixtures.CFG, "1").walk(new RatingsEngine.Member(m.id(), m.symbol(), m.industry(), longer), 0, n + extra - 1, List.of(),
                new BaseWalker.Sink() {
                    @Override
                    public void detected(Base base) {
                        last.add(base);
                    }

                    @Override
                    public void status(Base base) {
                        last.replaceAll(b -> b.id().equals(base.id()) ? base : b);
                    }
                });
        Base flat = last.stream().filter(b -> b.type() == BaseType.FLAT_BASE).findFirst().orElseThrow();
        assertThat(flat.status()).isEqualTo(BaseStatus.EXPIRED);
        assertThat(flat.statusDate()).isEqualTo(longer.date(longer.indexOf(flat.detectedDate()) + 60));
    }

    @Test
    void aCupGivesWayToTheCupWithHandleThatFormsFromIt() {
        Walk w = walk("cup_with_handle", 0, -1, List.of());
        assertThat(w.events()).contains("CUP FORMING", "CUP EXPIRED", "CUP_WITH_HANDLE NEAR_PIVOT");
        assertThat(w.events().indexOf("CUP EXPIRED")).isLessThan(w.events().indexOf("CUP_WITH_HANDLE NEAR_PIVOT"));
        assertThat(w.detected()).extracting(Base::type).filteredOn(t -> !t.reversal()).containsExactly(BaseType.CUP, BaseType.CUP_WITH_HANDLE);
        assertThat(w.finalOf(BaseType.CUP_WITH_HANDLE).pivot()).isEqualByComparingTo("146.75");
    }

    @Test
    void aReversalSetupUsesTheSessionLowAsStopAndTheNearerGoal() {
        Base b = walk("ma_reversal", 0, -1, List.of()).finalOf(BaseType.MA_REVERSAL);
        assertThat(b.pivot()).isEqualByComparingTo("154.60");
        assertThat(b.stop()).isEqualByComparingTo("151.00");
        assertThat(b.goal()).isEqualByComparingTo("166.95"); // x 1.08 = 166.968
        assertThat(b.status()).isEqualTo(BaseStatus.NEAR_PIVOT);
    }

    @Test
    void walkingInTwoPartsGivesTheSameBasesAsWalkingOnce() {
        Walk once = walk("lifecycle_goal", 0, -1, List.of());
        Walk firstPart = walk("lifecycle_goal", 0, 140, List.of());
        Walk secondPart = walk("lifecycle_goal", 141, -1, firstPart.last());
        assertThat(secondPart.last()).isEqualTo(once.last());
        List<String> events = new ArrayList<>(firstPart.events());
        events.addAll(secondPart.events());
        assertThat(events).isEqualTo(once.events());
    }

    @Test
    void aBaseNeverChangesAfterDetectionExceptItsStatus() {
        Walk w = walk("lifecycle_goal", 0, -1, List.of());
        Base detected = w.first(BaseType.FLAT_BASE);
        Base last = w.finalOf(BaseType.FLAT_BASE);
        assertThat(last.withStatus(detected.status(), detected.statusDate(), null, null, null, null, null, null)).isEqualTo(detected);
    }
}
