package money.hejje.regime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import money.hejje.market.indicators.Bar;
import org.junit.jupiter.api.Test;

/** Rule tests per PRD section 13 dimension with hand-built inputs (docs/regime.md thresholds). */
class RegimeRulesTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 9, 8);
    static final RegimeProperties PROPS = RegimeTestSupport.defaults();

    // --- trend ---

    @Test
    void trendLabelsFollowEmaStructureSlopeAndAdx() {
        assertThat(RegimeTestSupport.trend(20_400, 20_200, 20_000, 20_000, 30).label()).isEqualTo(Trend.STRONG_UP); // slope +1.0 %, ADX 30
        assertThat(RegimeTestSupport.trend(20_400, 20_200, 20_000, 20_190, 30).label()).isEqualTo(Trend.UP);        // slope too small for STRONG
        assertThat(RegimeTestSupport.trend(20_400, 20_200, 20_000, 20_000, 20).label()).isEqualTo(Trend.UP);        // ADX below 25
        assertThat(RegimeTestSupport.trend(20_400, 20_200, 20_000, 20_000, 12).label()).isEqualTo(Trend.RANGE);     // ADX below 18
        assertThat(RegimeTestSupport.trend(20_100, 20_200, 20_000, 20_000, 30).label()).isEqualTo(Trend.RANGE);     // close between the EMAs
        assertThat(RegimeTestSupport.trend(19_600, 19_800, 20_000, 20_000, 30).label()).isEqualTo(Trend.STRONG_DOWN);
        assertThat(RegimeTestSupport.trend(19_600, 19_800, 20_000, 19_810, 22).label()).isEqualTo(Trend.DOWN);
        assertThat(RegimeTestSupport.trend(20_400, 20_200, 20_000, 20_000, 30).evidence().get(0)).contains("STRONG_UP").contains("ADX 30.0");
    }

    @Test
    void trendIsUnknownWithoutEnoughDailyBars() {
        var unknown = RegimeTestSupport.trend(20_400, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        assertThat(unknown.label()).isEqualTo(Trend.UNKNOWN);
        assertThat(unknown.evidence().get(0)).contains("not enough daily bars");
    }

    // --- volatility ---

    @Test
    void volatilityBucketsByCombinedPercentile() {
        assertThat(RegimeTestSupport.volatility(5.0, 5.0).label()).isEqualTo(Volatility.VERY_LOW);
        assertThat(RegimeTestSupport.volatility(10.0, 10.0).label()).isEqualTo(Volatility.LOW);
        assertThat(RegimeTestSupport.volatility(20.0, 40.0).label()).isEqualTo(Volatility.NORMAL); // combined 30
        assertThat(RegimeTestSupport.volatility(80.0, 60.0).label()).isEqualTo(Volatility.HIGH);   // combined 70
        assertThat(RegimeTestSupport.volatility(95.0, 92.0).label()).isEqualTo(Volatility.EXTREME);
        assertThat(RegimeTestSupport.volatility(95.0, null).label()).isEqualTo(Volatility.EXTREME); // VIX only
        assertThat(RegimeTestSupport.volatility(null, 5.0).label()).isEqualTo(Volatility.VERY_LOW); // ATR only
        assertThat(RegimeTestSupport.volatility(null, null).label()).isEqualTo(Volatility.UNKNOWN);
        assertThat(RegimeTestSupport.volatility(80.0, 60.0).evidence().get(0)).contains("80th percentile").contains("combined 70");
    }

    // --- opening ---

    @Test
    void openingGapThresholdsAndRefinement() {
        assertThat(RegimeTestSupport.opening(20_000, 20_030, List.of()).label()).isEqualTo(Opening.FLAT);      // +0.15 %
        assertThat(RegimeTestSupport.opening(20_000, 20_100, List.of()).label()).isEqualTo(Opening.GAP_UP);    // +0.5 %, no bars yet
        assertThat(RegimeTestSupport.opening(20_000, 19_900, List.of()).label()).isEqualTo(Opening.GAP_DOWN);
        // opening range complete (bars to 09:30): close above the open continues the gap
        assertThat(RegimeTestSupport.opening(20_000, 20_100, bars(20_100, 20_110, 20_120, 20_130)).label()).isEqualTo(Opening.GAP_CONTINUATION);
        // close back through the open by more than half the gap rejects it
        assertThat(RegimeTestSupport.opening(20_000, 20_100, bars(20_100, 20_080, 20_040, 20_020)).label()).isEqualTo(Opening.GAP_REJECTION);
        // close between the open and the rejection level keeps GAP_UP
        assertThat(RegimeTestSupport.opening(20_000, 20_100, bars(20_100, 20_090, 20_080, 20_070)).label()).isEqualTo(Opening.GAP_UP);
        // gap down mirror
        assertThat(RegimeTestSupport.opening(20_000, 19_900, bars(19_900, 19_890, 19_880, 19_870)).label()).isEqualTo(Opening.GAP_CONTINUATION);
        assertThat(RegimeTestSupport.opening(20_000, 19_900, bars(19_900, 19_920, 19_960, 19_980)).label()).isEqualTo(Opening.GAP_REJECTION);
        // opening range not complete yet (bars to 09:25 only): no refinement
        assertThat(RegimeTestSupport.opening(20_000, 20_100, bars(20_100, 20_110)).label()).isEqualTo(Opening.GAP_UP);
        // a flat open is never refined
        assertThat(RegimeTestSupport.opening(20_000, 20_010, bars(20_010, 20_050, 20_080, 20_100)).label()).isEqualTo(Opening.FLAT);
        assertThat(RegimeTestSupport.opening(Double.NaN, 20_100, List.of()).label()).isEqualTo(Opening.UNKNOWN);
        assertThat(RegimeTestSupport.opening(20_000, 20_100, bars(20_100, 20_110, 20_120, 20_130)).evidence().get(0)).contains("gap +0.50%").contains("15-minute close");
    }

    // --- breadth ---

    @Test
    void breadthNeedsCoverageAndBucketsByAdvanceShare() {
        assertThat(RegimeTestSupport.breadth(50, 20, 5, 0).label()).isEqualTo(Breadth.UNKNOWN); // 25 of 50 = 50 % coverage
        assertThat(RegimeTestSupport.breadth(50, 40, 8, 0).label()).isEqualTo(Breadth.STRONG_POSITIVE); // 83 %
        assertThat(RegimeTestSupport.breadth(50, 30, 18, 0).label()).isEqualTo(Breadth.POSITIVE);       // 62.5 %
        assertThat(RegimeTestSupport.breadth(50, 24, 24, 0).label()).isEqualTo(Breadth.MIXED);
        assertThat(RegimeTestSupport.breadth(50, 15, 33, 0).label()).isEqualTo(Breadth.NEGATIVE);       // 31 %
        assertThat(RegimeTestSupport.breadth(50, 5, 43, 0).label()).isEqualTo(Breadth.STRONG_NEGATIVE); // 10 %
        // VWAP share blends in: 40/8 advances (83 %) but only 12 of 48 above VWAP (25 %) -> 54 % -> MIXED
        assertThat(RegimeTestSupport.breadth(50, 40, 8, 12).label()).isEqualTo(Breadth.MIXED);
        assertThat(RegimeTestSupport.breadth(50, 40, 8, 12).evidence().get(0)).contains("40 advances / 8 declines").contains("12 of 48 above VWAP");
        assertThat(RegimeTestSupport.breadth(0, 0, 0, 0).label()).isEqualTo(Breadth.UNKNOWN);
    }

    // --- intraday structure ---

    @Test
    void structureLabels() {
        double atr = 160; // daily ATR
        // trend day: opening range 20_000-20_050, then a steady climb to 20_400 closing at the high, never below the session average
        List<Bar> trend = new ArrayList<>(bars(20_020, 20_040, 20_030));
        for (int i = 0; i < 40; i++) {
            trend.add(bar(trend.size(), 20_050 + i * 9, 20_060 + i * 9));
        }
        var t = RegimeTestSupport.structure(trend, atr, false);
        assertThat(t.label()).isEqualTo(IntradayStructure.TREND_DAY);
        assertThat(t.evidence().get(0)).contains("progressive").contains("VWAP crosses");
        assertThat(RegimeTestSupport.structure(trend, atr, true).evidence().get(0)).doesNotContain("progressive");

        // compression: whole day inside 60 points (0.375 ATR)
        List<Bar> tight = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tight.add(bar(i, 20_000 + (i % 2) * 30, 20_000 + ((i + 1) % 2) * 30));
        }
        assertThat(RegimeTestSupport.structure(tight, atr, false).label()).isEqualTo(IntradayStructure.LOW_VOLATILITY_COMPRESSION);

        // chop: 200-point swings back and forth across the average (1.25 ATR) with many crosses
        List<Bar> chop = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            chop.add(bar(i, 20_000 + (i % 2) * 200, 20_000 + ((i + 1) % 2) * 200));
        }
        assertThat(RegimeTestSupport.structure(chop, atr, false).label()).isEqualTo(IntradayStructure.HIGH_VOLATILITY_CHOP);

        // reversal: rallies 200 points in the morning, then sells off to close 100 below the open
        List<Bar> reversal = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reversal.add(bar(i, 20_000 + i * 10, 20_010 + i * 10));
        }
        for (int i = 0; i < 30; i++) {
            reversal.add(bar(20 + i, 20_200 - i * 10, 20_190 - i * 10));
        }
        assertThat(RegimeTestSupport.structure(reversal, atr, false).label()).isEqualTo(IntradayStructure.REVERSAL_DAY);

        // range day: a 120-point box (0.75 ATR) with a couple of crosses, closing mid-range
        List<Bar> range = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            range.add(bar(i, 20_000 + (i % 4) * 40, 20_000 + ((i + 1) % 4) * 40));
        }
        assertThat(RegimeTestSupport.structure(range, atr, false).label()).isEqualTo(IntradayStructure.RANGE_DAY);

        assertThat(RegimeTestSupport.structure(trend.subList(0, 5), atr, false).label()).isEqualTo(IntradayStructure.UNKNOWN);
        // without a daily ATR the trend rule still works
        assertThat(RegimeTestSupport.structure(trend, Double.NaN, false).label()).isEqualTo(IntradayStructure.TREND_DAY);
    }

    @Test
    void preferenceKeysMatchSnapshots() {
        RegimeSnapshot s = new RegimeSnapshot(DAY, null, Trend.STRONG_UP, Volatility.HIGH, Opening.GAP_CONTINUATION, Breadth.POSITIVE,
                IntradayStructure.TREND_DAY, EventEnvironment.NORMAL, null, null, "1", true);
        assertThat(s.matches("trending")).isTrue();
        assertThat(s.matches("trending_up")).isTrue();
        assertThat(s.matches("trending_down")).isFalse();
        assertThat(s.matches("ranging")).isFalse();
        assertThat(s.matches("volatile")).isTrue();
        assertThat(s.matches("quiet")).isFalse();
        assertThat(s.matches("gap")).isTrue();
        assertThat(s.matches("strong_up")).isTrue();
        assertThat(s.matches("trend_day")).isTrue();
        assertThat(s.matches("expiry")).isFalse();
        assertThat(s.matches("nonsense")).isFalse();
        assertThat(s.key()).isEqualTo("STRONG_UP × HIGH");
    }

    // --- helpers ---

    /** M5 bars from 09:15 with the given closes (open = previous close); bar {@code i} closes at 09:15 + 5(i+1). */
    static List<Bar> bars(double... closes) {
        List<Bar> out = new ArrayList<>();
        double prev = closes[0];
        for (int i = 0; i < closes.length; i++) {
            out.add(bar(i, prev, closes[i]));
            prev = closes[i];
        }
        return out;
    }

    static Bar bar(int index, double open, double close) {
        LocalTime start = LocalTime.of(9, 15).plusMinutes(5L * index);
        var openTime = DAY.atTime(start).atZone(IST).toInstant();
        return new Bar(openTime, openTime.plusSeconds(300), DAY, start, start.plusMinutes(5), open, Math.max(open, close), Math.min(open, close), close, 0, false);
    }
}
