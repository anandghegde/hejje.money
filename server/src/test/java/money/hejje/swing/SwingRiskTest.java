package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.risk.RiskCheck;
import org.junit.jupiter.api.Test;

/** Plan M11.3: each swing limit rejects the entry that would breach it, by name; sizing follows the gap-adjusted budget. */
class SwingRiskTest {

    static final SwingLimits LIMITS = new SwingLimits(ExecutionMode.PAPER, Money.ofRupees(500_000), 3, Money.ofRupees(2_500), new BigDecimal("3.00"),
            Money.ofRupees(6_000), 1, true, true);
    static final SwingRisk.Context CLEAR = new SwingRisk.Context(ExecutionMode.PAPER, List.of(), null, "NONE");
    static final UUID NEW = UUID.randomUUID();

    static SwingRisk.Holding holding(int qty, String price, String stop, String industry) {
        return new SwingRisk.Holding(UUID.randomUUID(), "NSE:X", qty, new BigDecimal(price), new BigDecimal(price), stop == null ? null : new BigDecimal(stop), industry);
    }

    static SwingRisk.Entry entry(int qty, String price, String stop, String industry) {
        return new SwingRisk.Entry(NEW, qty, new BigDecimal(price), stop == null ? null : new BigDecimal(stop), industry);
    }

    static RiskCheck named(List<RiskCheck> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no check " + name + " in " + checks));
    }

    static void rejectsBy(List<RiskCheck> checks, String name) {
        assertThat(named(checks, name).passed()).as(name).isFalse();
        assertThat(checks.stream().filter(c -> !c.passed()).map(RiskCheck::name)).containsExactly(name);
    }

    @Test
    void aPositionRisksItsStopDistancePlusTheGapAllowance() {
        assertThat(SwingRisk.risk(10, new BigDecimal("100.00"), new BigDecimal("93.00"), new BigDecimal("3"))).isEqualTo(Money.of("100.00")); // 10 × (7 + 3)
        assertThat(SwingRisk.risk(10, new BigDecimal("90.00"), new BigDecimal("93.00"), new BigDecimal("3"))).isEqualTo(Money.of("27.00")); // below the stop: the gap
        assertThat(SwingRisk.risk(10, new BigDecimal("100.00"), null, new BigDecimal("3"))).isEqualTo(Money.of("1000.00")); // no stop: the whole value
        assertThat(SwingRisk.overnightRisk(List.of(holding(10, "100.00", "93.00", "A"), holding(20, "50.00", "45.00", "B")), new BigDecimal("3")))
                .isEqualTo(Money.of("230.00")); // 100 + 20 × (5 + 1.5)
    }

    @Test
    void anEntryWithinEveryLimitPasses() {
        List<RiskCheck> checks = SwingRisk.check(LIMITS, List.of(holding(10, "100.00", "93.00", "Energy")), entry(100, "100.00", "93.00", "IT"), CLEAR);
        assertThat(checks).allMatch(RiskCheck::passed);
        assertThat(named(checks, "swingOvernightRisk").message()).isEqualTo("1100.00"); // 100 + 100 × 10
    }

    @Test
    void swingIsPaperOnly() {
        rejectsBy(SwingRisk.check(LIMITS, List.of(), entry(10, "100.00", "93.00", "IT"), new SwingRisk.Context(ExecutionMode.AUTO, List.of(), null, "NONE")),
                "swingPaperOnly");
    }

    @Test
    void anUnprotectedPositionBlocksNewEntries() {
        rejectsBy(SwingRisk.check(LIMITS, List.of(), entry(10, "100.00", "93.00", "IT"),
                new SwingRisk.Context(ExecutionMode.PAPER, List.of("NSE:INFY"), null, "NONE")), "swingProtection");
    }

    @Test
    void anEntryNeedsAStopBelowItsPrice() {
        rejectsBy(SwingRisk.check(LIMITS, List.of(), entry(10, "100.00", null, "IT"), CLEAR), "swingStop");
        rejectsBy(SwingRisk.check(LIMITS, List.of(), entry(10, "100.00", "101.00", "IT"), CLEAR), "swingStop");
    }

    @Test
    void maxOpenPositions() {
        List<SwingRisk.Holding> three = List.of(holding(1, "100.00", "95.00", "A"), holding(1, "100.00", "95.00", "B"), holding(1, "100.00", "95.00", "C"));
        rejectsBy(SwingRisk.check(LIMITS, three, entry(10, "100.00", "93.00", "D"), CLEAR), "swingOpenPositions");
    }

    @Test
    void maxRiskPerPositionIsGapAdjusted() {
        // 240 × (7 + 3) = 2,400 fits; 260 × 10 = 2,600 does not, although the stop distance alone (1,820) would
        assertThat(SwingRisk.check(LIMITS, List.of(), entry(240, "100.00", "93.00", "IT"), CLEAR)).allMatch(RiskCheck::passed);
        rejectsBy(SwingRisk.check(LIMITS, List.of(), entry(260, "100.00", "93.00", "IT"), CLEAR), "swingRiskPerPosition");
    }

    @Test
    void maxOvernightRisk() {
        List<SwingRisk.Holding> book = List.of(holding(240, "100.00", "93.00", "A"), holding(240, "100.00", "93.00", "B")); // 4,800
        rejectsBy(SwingRisk.check(LIMITS, book, entry(150, "100.00", "93.00", "C"), CLEAR), "swingOvernightRisk"); // + 1,500 > 6,000
    }

    @Test
    void swingCapital() {
        List<SwingRisk.Holding> book = List.of(holding(4_990, "100.00", "99.99", "A")); // 4,99,000 deployed, risk 15,000 × ... kept tiny by the stop
        SwingLimits roomy = new SwingLimits(ExecutionMode.PAPER, Money.ofRupees(500_000), 3, Money.ofRupees(2_500), new BigDecimal("0"),
                Money.ofRupees(100_000), 1, true, true);
        rejectsBy(SwingRisk.check(roomy, book, entry(20, "100.00", "95.00", "B"), CLEAR), "swingCapital");
    }

    @Test
    void maxPositionsPerIndustry() {
        List<SwingRisk.Holding> book = List.of(holding(10, "100.00", "93.00", "Information Technology"));
        rejectsBy(SwingRisk.check(LIMITS, book, entry(10, "100.00", "93.00", "Information Technology"), CLEAR), "swingIndustry");
        assertThat(named(SwingRisk.check(LIMITS, book, entry(10, "100.00", "93.00", null), CLEAR), "swingIndustry").passed()).isTrue();
    }

    @Test
    void noEntryOnTheSessionBeforeABlockingEvent() {
        SwingRisk.Context rbi = new SwingRisk.Context(ExecutionMode.PAPER, List.of(), "RBI_POLICY on 2026-12-04", "NONE");
        rejectsBy(SwingRisk.check(LIMITS, List.of(), entry(10, "100.00", "93.00", "IT"), rbi), "swingEventNextSession");
        SwingLimits off = new SwingLimits(ExecutionMode.PAPER, LIMITS.swingCapital(), 3, LIMITS.maxRiskPerPosition(), LIMITS.gapAllowancePct(),
                LIMITS.maxOvernightRisk(), 1, false, true);
        assertThat(SwingRisk.check(off, List.of(), entry(10, "100.00", "93.00", "IT"), rbi)).allMatch(RiskCheck::passed);
    }

    @Test
    void noEntryInAStockUnderSurveillance() {
        rejectsBy(SwingRisk.check(LIMITS, List.of(), entry(10, "100.00", "93.00", "IT"), new SwingRisk.Context(ExecutionMode.PAPER, List.of(), null, "ASM_ST_I")),
                "swingSurveillance");
        assertThat(SwingRisk.check(LIMITS, List.of(), entry(10, "100.00", "93.00", "IT"), new SwingRisk.Context(ExecutionMode.PAPER, List.of(), null, null)))
                .allMatch(RiskCheck::passed); // no lists fetched: not blocking
    }

    @Test
    void sizingUsesTheGapAdjustedBudgetAndNamesTheBindingLimit() {
        SwingRisk.Size free = SwingRisk.size(LIMITS, List.of(), new BigDecimal("100.00"), new BigDecimal("93.00"));
        assertThat(free.riskPerShare()).isEqualByComparingTo("10.00");
        assertThat(free.quantity()).isEqualTo(250);
        assertThat(free.limitedBy()).isEqualTo("riskPerPosition");

        List<SwingRisk.Holding> book = new ArrayList<>(List.of(holding(500, "100.00", "93.00", "A"))); // 5,000 of the 6,000 budget used
        SwingRisk.Size tight = SwingRisk.size(LIMITS, book, new BigDecimal("100.00"), new BigDecimal("93.00"));
        assertThat(tight.quantity()).isEqualTo(100);
        assertThat(tight.limitedBy()).isEqualTo("overnightRisk");

        assertThat(SwingRisk.size(LIMITS, List.of(), new BigDecimal("100.00"), new BigDecimal("100.00")).quantity()).isZero();
    }
}
