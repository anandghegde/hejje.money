package money.hejje.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.backtest.internal.RegimeGrouping;
import money.hejje.regime.Breadth;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.IntradayStructure;
import money.hejje.regime.Opening;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.regime.Trend;
import money.hejje.regime.Volatility;
import org.junit.jupiter.api.Test;

class RegimeGroupingTest {

    static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    static final LocalDate D2 = LocalDate.of(2026, 9, 2);
    static final LocalDate D3 = LocalDate.of(2026, 9, 3);

    @Test
    void groupsTradesByEntrySessionLabelAndFindsTheSimilarBucket() {
        Map<LocalDate, RegimeSnapshot> labels = Map.of(D1, snap(D1, Trend.UP, Volatility.NORMAL), D2, snap(D2, Trend.UP, Volatility.NORMAL),
                D3, snap(D3, Trend.RANGE, Volatility.LOW));
        List<BacktestTrade> trades = new ArrayList<>();
        trades.addAll(ScoreTrades.on(D1, 2.0, 2.0));   // UP × NORMAL winners
        trades.addAll(ScoreTrades.on(D2, -1.0));       // UP × NORMAL loser
        trades.addAll(ScoreTrades.on(D3, -1.0, -1.0)); // RANGE × LOW losers
        trades.addAll(ScoreTrades.on(D3.plusDays(10), 1.0)); // unlabelled session

        RegimeBreakdown b = RegimeGrouping.group(trades, labels, snap(D3.plusDays(10), Trend.UP, Volatility.NORMAL), null, SyntheticSessions.IST);
        assertThat(b.dims()).containsExactly("trend", "volatility");
        assertThat(b.byRegime()).extracting(RegimeBreakdown.Bucket::key).containsExactly("RANGE × LOW", "UNKNOWN × UNKNOWN", "UP × NORMAL");
        RegimeBreakdown.Bucket up = b.byRegime().get(2);
        assertThat(up.trades()).isEqualTo(3);
        assertThat(up.winRate()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(up.expectancyR()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(up.profitFactor()).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(b.similar()).isNotNull();
        assertThat(b.similar().current()).isEqualTo("UP × NORMAL");
        assertThat(b.similar().trades()).isEqualTo(3);
        assertThat(b.similar().overallTrades()).isEqualTo(6);
        assertThat(b.similar().overallExpectancyR()).isCloseTo(2.0 / 6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(b.note()).isNull();
    }

    @Test
    void similarIsAbsentWhenTheCurrentRegimeIsUnknownOrUnseen() {
        Map<LocalDate, RegimeSnapshot> labels = Map.of(D1, snap(D1, Trend.UP, Volatility.NORMAL));
        List<BacktestTrade> trades = ScoreTrades.on(D1, 1.0);
        RegimeBreakdown unknown = RegimeGrouping.group(trades, labels, snap(D2, Trend.UNKNOWN, Volatility.NORMAL), null, SyntheticSessions.IST);
        assertThat(unknown.similar()).isNull();
        assertThat(unknown.note()).contains("current regime unknown");
        RegimeBreakdown unseen = RegimeGrouping.group(trades, labels, snap(D2, Trend.DOWN, Volatility.HIGH), List.of("trend"), SyntheticSessions.IST);
        assertThat(unseen.similar()).isNull();
        assertThat(unseen.note()).contains("no backtest trades in the current regime (DOWN)");
        assertThat(unseen.byRegime()).extracting(RegimeBreakdown.Bucket::key).containsExactly("UP");
        RegimeBreakdown none = RegimeGrouping.group(trades, labels, null, null, SyntheticSessions.IST);
        assertThat(none.similar()).isNull();
    }

    @Test
    void rejectsUnknownDimensions() {
        assertThatThrownBy(() -> RegimeGrouping.group(List.of(), Map.of(), null, List.of("weather"), SyntheticSessions.IST))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weather");
    }

    static RegimeSnapshot snap(LocalDate date, Trend trend, Volatility vol) {
        return new RegimeSnapshot(date, date.atTime(15, 30).atZone(SyntheticSessions.IST).toInstant(), trend, vol, Opening.FLAT, Breadth.MIXED,
                IntradayStructure.RANGE_DAY, EventEnvironment.NORMAL, Map.of(), List.of(), "1", true);
    }

    /** Trades entered on a session with the given R multiples (net money = R × 1000). */
    static final class ScoreTrades {
        static List<BacktestTrade> on(LocalDate day, double... rs) {
            List<BacktestTrade> out = new ArrayList<>();
            for (double r : rs) {
                money.hejje.common.Money net = money.hejje.common.Money.ofRupees((long) (r * 1000));
                out.add(new BacktestTrade(UUID.randomUUID(), null, UUID.randomUUID(), Split.IN_SAMPLE, day.atTime(10, 0).atZone(SyntheticSessions.IST).toInstant(),
                        day.atTime(11, 0).atZone(SyntheticSessions.IST).toInstant(), money.hejje.common.Side.BUY, 1, java.math.BigDecimal.TEN,
                        java.math.BigDecimal.TEN, java.math.BigDecimal.ONE, null, net, money.hejje.common.Money.ZERO, net, r, ExitReason.TARGET, List.of()));
            }
            return out;
        }
    }
}
