package money.hejje.ratings.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.InstrumentType;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.costs.CostFill;
import money.hejje.common.costs.CostModel;
import money.hejje.common.costs.CostProperties;
import money.hejje.ratings.Base;
import money.hejje.ratings.BaseStatus;
import money.hejje.swing.SwingBacktest;
import org.junit.jupiter.api.Test;

/**
 * Plan M11.5 parity: the H5 ledger (the M8.4 lifecycle that produced the past-setups ledger) and the SWING backtest run with
 * the ledger's rules agree on every setup of the fixture universe: the same triggers, entries, exits and R; the backtest's
 * net R differs from the ledger's R by the delivery costs only.
 */
class SwingParityTest {

    /** The fixture universe: every golden D1 series of the base detectors and the lifecycle. */
    static final List<String> UNIVERSE = List.of("flat_base", "cup_with_handle", "double_bottom", "ma_reversal", "lifecycle_goal", "lifecycle_stopped",
            "lifecycle_failed");

    static final CostModel COSTS = new CostModel(new CostProperties(false, new BigDecimal("20"), new BigDecimal("0.0003"), new BigDecimal("0.18"),
            new BigDecimal("0.000001"),
            new CostProperties.Segments(new BigDecimal("0.00025"), true, new BigDecimal("0.0000307"), new BigDecimal("0.00003"), false),
            new CostProperties.Segments(new BigDecimal("0.001"), false, new BigDecimal("0.0000307"), new BigDecimal("0.00015"), true),
            new CostProperties.Segments(new BigDecimal("0.0005"), true, new BigDecimal("0.0000183"), new BigDecimal("0.00002"), false),
            new CostProperties.Segments(new BigDecimal("0.0015"), true, new BigDecimal("0.0003553"), new BigDecimal("0.00003"), false)),
            new BigDecimal("15.34"));

    static List<SwingBacktest.Bar> bars(DailySeries s) {
        List<SwingBacktest.Bar> out = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) {
            out.add(new SwingBacktest.Bar(s.date(i), BigDecimal.valueOf(s.open()[i]), BigDecimal.valueOf(s.high()[i]), BigDecimal.valueOf(s.low()[i]),
                    BigDecimal.valueOf(s.close()[i]), (long) s.volume()[i]));
        }
        return out;
    }

    @Test
    void theSwingBacktestWithTheLedgersRulesReproducesTheLedgerWithinCosts() {
        int compared = 0;
        int untriggered = 0;
        for (String fixture : UNIVERSE) {
            BaseLifecycleTest.Walk walk = BaseLifecycleTest.walk(fixture, 0, -1, List.of());
            RatingsEngine.Member m = BaseFixtures.member(fixture);
            UUID instrument = UUID.nameUUIDFromBytes(fixture.getBytes());
            Map<UUID, List<SwingBacktest.Bar>> bars = Map.of(instrument, bars(m.series()));
            Map<UUID, Base> finals = new LinkedHashMap<>();
            walk.last().forEach(b -> finals.put(b.id(), b));
            for (Base detected : walk.detected()) {
                Base ledger = finals.get(detected.id());
                // the ledger replaces an untriggered cup by its cup-with-handle on the day the handle qualifies (BaseWalker)
                java.time.LocalDate superseded = detected.type() == money.hejje.ratings.BaseType.CUP && ledger.triggerDate() == null
                        && walk.detected().stream().anyMatch(o -> o.type() == money.hejje.ratings.BaseType.CUP_WITH_HANDLE && o.detectedDate().equals(ledger.statusDate()))
                        ? ledger.statusDate() : null;
                SwingBacktest.Plan plan = new SwingBacktest.Plan(detected.id(), instrument, fixture, detected.type(), detected.detectedDate(), detected.baseLow(),
                        detected.pivot(), detected.buyHigh(), detected.stop(), detected.goal(), superseded);
                SwingBacktest.Result r = SwingBacktest.run(bars, List.of(plan),
                        SwingBacktest.Config.ledger(BaseFixtures.CFG.maxHoldSessions(), BaseFixtures.CFG.expireSessions(), Money.ofRupees(2_500), new BigDecimal("3")),
                        (side, qty, price) -> COSTS.compute(new CostFill(InstrumentType.EQ, Product.CNC, side, qty, price)).total());
                String what = fixture + " " + detected.type() + " " + ledger.status();
                if (ledger.triggerDate() == null) {
                    // FAILED or EXPIRED before a trigger, or still waiting when the data ends: no trade either way
                    assertThat(r.trades()).as(what).isEmpty();
                    SwingBacktest.NoTrade expected = ledger.status() == BaseStatus.FAILED ? SwingBacktest.NoTrade.FAILED
                            : ledger.status() == BaseStatus.EXPIRED ? SwingBacktest.NoTrade.EXPIRED : SwingBacktest.NoTrade.OPEN;
                    assertThat(r.notTraded()).as(what).containsOnlyKeys(expected);
                    untriggered++;
                    continue;
                }
                if (!ledger.status().closed()) {
                    assertThat(r.trades()).as(what + " (still open in the ledger)").isEmpty();
                    continue;
                }
                assertThat(r.trades()).as(what).singleElement().satisfies(t -> {
                    assertThat(t.entryDate()).isEqualTo(ledger.triggerDate());
                    assertThat(t.entry()).isEqualByComparingTo(ledger.entry());
                    assertThat(t.exitDate()).isEqualTo(ledger.statusDate());
                    assertThat(t.exit()).isEqualByComparingTo(ledger.exit());
                    assertThat(t.reason().name()).isEqualTo(ledger.status() == BaseStatus.EXPIRED ? "TIME_EXIT" : ledger.status().name());
                    assertThat(t.grossR()).isEqualTo(ledger.outcomeR());
                    // within costs: net R = the ledger's R less the delivery costs over the money at risk
                    double costsR = t.costs().toRupees().doubleValue() / (t.quantity() * t.entry().subtract(ledger.stop()).doubleValue());
                    assertThat(t.netR()).isCloseTo(ledger.outcomeR() - costsR, org.assertj.core.data.Offset.offset(0.011));
                    assertThat(t.netR()).isLessThan(ledger.outcomeR());
                });
                compared++;
            }
        }
        assertThat(compared).as("closed, triggered setups compared").isGreaterThanOrEqualTo(3);
        assertThat(untriggered).as("setups that never triggered, compared").isGreaterThanOrEqualTo(1);
    }
}
