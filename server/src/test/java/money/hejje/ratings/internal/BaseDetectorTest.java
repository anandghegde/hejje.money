package money.hejje.ratings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.Optional;
import money.hejje.ratings.BaseType;
import org.junit.jupiter.api.Test;

class BaseDetectorTest {

    final BaseDetector detector = new BaseDetector(BaseFixtures.CFG);

    /** The first session on which the detector answers, scanning forward like the walker does. */
    Optional<BaseDetector.Detected> first(String fixture, BaseType type) {
        DailySeries s = BaseFixtures.series(fixture);
        for (int i = 0; i < s.size(); i++) {
            Optional<BaseDetector.Detected> d = type.reversal() ? detector.reversal(s, i) : detector.base(s, i);
            if (d.isPresent() && d.get().type() == type) {
                return d;
            }
        }
        return Optional.empty();
    }

    boolean anyBase(String fixture) {
        DailySeries s = BaseFixtures.series(fixture);
        for (int i = 0; i < s.size(); i++) {
            if (detector.base(s, i).isPresent()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void flatBaseIsFoundOnceItIsTwentyFiveSessionsLong() {
        BaseDetector.Detected d = first("flat_base", BaseType.FLAT_BASE).orElseThrow();
        assertThat(d.end() - d.start()).isEqualTo(25);
        assertThat(d.pivot()).isEqualTo(150.75);            // the left high: 150 x 1.005
        assertThat(d.depthPct()).isCloseTo(8.9, offset(0.2)); // 150.75 -> 138 x 0.995
        assertThat(d.evidence()).containsKeys("leftHigh", "low").containsEntry("sessions", 25);
    }

    @Test
    void flatBaseNearMissesAreRejected() {
        assertThat(anyBase("flat_base_too_deep")).isFalse();   // 17 % deep, and no recovery that would make it a cup
        assertThat(anyBase("flat_base_too_short")).isFalse();  // 20 sessions
        assertThat(anyBase("flat_base_no_uptrend")).isFalse(); // an 18 % advance into the left high
    }

    @Test
    void cupWithHandlePivotsAtTheHandleHigh() {
        BaseDetector.Detected d = first("cup_with_handle", BaseType.CUP_WITH_HANDLE).orElseThrow();
        assertThat(d.pivot()).isEqualTo(146.73);              // the right high: 146 x 1.005
        assertThat(d.depthPct()).isCloseTo(26.1, offset(0.2)); // 150.75 -> 112 x 0.995
        assertThat(d.evidence()).containsEntry("handleSessions", 5).containsKeys("leftHigh", "low", "rightHigh", "handleLow", "handleDepthPct");
    }

    @Test
    void aCupWithoutAHandlePivotsAtTheLeftHighOnceTheRightSideRecoversNinetyPercent() {
        BaseDetector.Detected d = first("cup_with_handle", BaseType.CUP).orElseThrow();
        assertThat(d.pivot()).isEqualTo(150.75);
        DailySeries s = BaseFixtures.series("cup_with_handle");
        assertThat(s.high()[d.end()]).isGreaterThanOrEqualTo(150.75 * 0.9);
        assertThat(s.high()[d.end() - 1]).isLessThan(150.75 * 0.9);
    }

    @Test
    void cupNearMissesAreRejected() {
        assertThat(first("cup_too_deep", BaseType.CUP_WITH_HANDLE)).isEmpty();        // 42 % deep
        assertThat(first("cup_too_deep", BaseType.CUP)).isEmpty();
        assertThat(first("cup_too_short", BaseType.CUP_WITH_HANDLE)).isEmpty();       // 22 sessions high to high
        assertThat(first("cup_too_short", BaseType.CUP)).isEmpty();
        assertThat(first("cup_handle_too_deep", BaseType.CUP_WITH_HANDLE)).isEmpty(); // a 15 % handle into the lower half
    }

    @Test
    void doubleBottomPivotsAtTheMiddlePeak() {
        BaseDetector.Detected d = first("double_bottom", BaseType.DOUBLE_BOTTOM).orElseThrow();
        assertThat(d.pivot()).isEqualTo(135.67);  // 135 x 1.005 as written to the fixture
        assertThat(d.baseLow()).isEqualTo(117.91); // the undercut: 118.5 x 0.995
        assertThat(d.evidence()).containsKeys("firstLow", "middlePeak", "secondLow");
    }

    @Test
    void doubleBottomNearMissesAreRejected() {
        assertThat(anyBase("double_bottom_lows_apart")).isFalse(); // the second low is 8 % under the first
        assertThat(anyBase("double_bottom_no_uptrend")).isFalse();
    }

    @Test
    void movingAverageReversalPivotsAtTheSessionHighWithTheLowAsStop() {
        BaseDetector.Detected d = first("ma_reversal", BaseType.MA_REVERSAL).orElseThrow();
        DailySeries s = BaseFixtures.series("ma_reversal");
        assertThat(d.end()).isEqualTo(s.size() - 1);
        assertThat(d.pivot()).isEqualTo(154.6);
        assertThat(d.stop()).isEqualTo(151.0);
        assertThat(d.evidence()).containsEntry("movingAverage", 50);
        assertThat(first("ma_reversal_weak_close", BaseType.MA_REVERSAL)).isEmpty(); // closes in the lower half of its range
    }

    @Test
    void detectionNeverReadsLaterSessions() {
        DailySeries full = BaseFixtures.series("lifecycle_goal");
        BaseDetector.Detected d = first("lifecycle_goal", BaseType.FLAT_BASE).orElseThrow();
        DailySeries cut = RatingsEngineTest.truncate(full, d.end() + 1);
        assertThat(detector.base(cut, d.end())).contains(d);
    }
}
