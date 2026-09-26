package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Money;
import money.hejje.ratings.BaseType;
import org.junit.jupiter.api.Test;

/** Plan M11.5: the SWING backtest's fills on daily bars (gap-through stops, stop first inside a bar, volume, time exit, costs). */
class SwingBacktestTest {

    static final UUID ID = UUID.randomUUID();
    static final LocalDate START = LocalDate.of(2025, 1, 1);
    static final SwingBacktest.Costs NO_COSTS = (side, qty, price) -> Money.ZERO;
    static final SwingBacktest.Config LIVE = SwingBacktest.Config.swing(new BigDecimal("1.4"), 30, 60, Money.ofRupees(2_500), new BigDecimal("3"));

    /** 60 quiet bars at 98 (volume 1,00,000), then {@code tail} as "open high low close volume". */
    static List<SwingBacktest.Bar> bars(String... tail) {
        List<SwingBacktest.Bar> out = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            out.add(bar(i, "98 98.5 97.5 98 100000"));
        }
        for (int i = 0; i < tail.length; i++) {
            out.add(bar(60 + i, tail[i]));
        }
        return out;
    }

    static SwingBacktest.Bar bar(int i, String ohlcv) {
        String[] f = ohlcv.split(" ");
        return new SwingBacktest.Bar(START.plusDays(i), new BigDecimal(f[0]), new BigDecimal(f[1]), new BigDecimal(f[2]), new BigDecimal(f[3]), Long.parseLong(f[4]));
    }

    static SwingBacktest.Plan plan(BaseType type) {
        // detected on the last quiet bar; pivot 100, buy zone to 105, stop 93, goal 120
        return new SwingBacktest.Plan(UUID.randomUUID(), ID, "NSE:X", type, START.plusDays(59), new BigDecimal("90"), new BigDecimal("100"),
                new BigDecimal("105"), new BigDecimal("93"), new BigDecimal("120"));
    }

    static SwingBacktest.Result run(SwingBacktest.Config cfg, List<SwingBacktest.Bar> bars, BaseType type) {
        return SwingBacktest.run(Map.of(ID, bars), List.of(plan(type)), cfg, NO_COSTS);
    }

    @Test
    void aGapThroughTheStopFillsAtTheOpenNotAtTheStop() {
        SwingBacktest.Result r = run(LIVE, bars("99 101 98.5 100.5 200000", "101 102 100 101 100000", "90 91 88 89 100000"), BaseType.FLAT_BASE);
        assertThat(r.trades()).singleElement().satisfies(t -> {
            assertThat(t.entry()).isEqualByComparingTo("100");
            assertThat(t.reason()).isEqualTo(SwingBacktest.Exit.STOPPED);
            assertThat(t.exit()).isEqualByComparingTo("90.00"); // the open, below the 93 stop
            assertThat(t.gapFill()).isTrue();
            assertThat(t.grossR()).isEqualTo(-1.43);             // (90 - 100) / 7
            assertThat(t.holdingDays()).isEqualTo(2);
        });
    }

    @Test
    void insideOneBarTheStopComesFirstAndTheEntrySessionCounts() {
        // entered at 100 and the same session trades down to 92 and up to 121: stopped, at the stop
        SwingBacktest.Result r = run(LIVE, bars("99 121 92 100 200000"), BaseType.FLAT_BASE);
        assertThat(r.trades()).singleElement().satisfies(t -> {
            assertThat(t.reason()).isEqualTo(SwingBacktest.Exit.STOPPED);
            assertThat(t.exit()).isEqualByComparingTo("93.00");
            assertThat(t.holdingDays()).isZero();
        });
    }

    @Test
    void aBreakoutWithoutVolumeIsConsumedAndAGapAboveTheZoneIsNotChased() {
        assertThat(run(LIVE, bars("99 101 98.5 100.5 120000", "101 110 100.5 109 300000"), BaseType.FLAT_BASE).notTraded())
                .containsEntry(SwingBacktest.NoTrade.NO_VOLUME, 1);
        assertThat(run(LIVE, bars("107 108 106 107 300000"), BaseType.FLAT_BASE).notTraded()).containsEntry(SwingBacktest.NoTrade.ABOVE_BUY_ZONE, 1);
        // a reversal has no volume condition
        assertThat(run(LIVE, bars("99 101 98.5 100.5 120000", "101 121 100.5 120.5 100000"), BaseType.MA_REVERSAL).trades()).hasSize(1);
    }

    @Test
    void aTradeThatReachesNeitherGoalNorStopIsClosedAtTheOpenAfterItsHoldingLimit() {
        String[] tail = new String[35];
        tail[0] = "99 101 98.5 100.5 200000";
        for (int i = 1; i < tail.length; i++) {
            tail[i] = (100 + i * 0.1) + " " + (101 + i * 0.1) + " 99 " + (100.5 + i * 0.1) + " 100000";
        }
        SwingBacktest.Result r = run(LIVE, bars(tail), BaseType.FLAT_BASE);
        assertThat(r.trades()).singleElement().satisfies(t -> {
            assertThat(t.reason()).isEqualTo(SwingBacktest.Exit.TIME_EXIT);
            assertThat(t.holdingDays()).isEqualTo(31);
            assertThat(t.exit()).isEqualByComparingTo(new BigDecimal(tail[31].split(" ")[0]).setScale(2, java.math.RoundingMode.HALF_UP));
        });
    }

    @Test
    void deliveryCostsComeOffTheGrossAndTheQuantityRisksTheBudgetOnTheGapAdjustedStop() {
        SwingBacktest.Costs flat = (side, qty, price) -> Money.ofRupees(side == money.hejje.common.Side.SELL ? 20 : 5);
        SwingBacktest.Result r = SwingBacktest.run(Map.of(ID, bars("99 101 98.5 100.5 200000", "101 121 100.5 120.5 100000")), List.of(plan(BaseType.FLAT_BASE)),
                LIVE, flat);
        assertThat(r.trades()).singleElement().satisfies(t -> {
            assertThat(t.quantity()).isEqualTo(250);                // 2,500 / (7 + 3 % of 100)
            assertThat(t.gross()).isEqualTo(Money.ofRupees(5_000)); // (120 - 100) × 250
            assertThat(t.costs()).isEqualTo(Money.ofRupees(25));
            assertThat(t.grossR()).isEqualTo(2.86);
            assertThat(t.netR()).isEqualTo(2.843);                  // 4,975 / (250 × 7)
        });
    }
}
