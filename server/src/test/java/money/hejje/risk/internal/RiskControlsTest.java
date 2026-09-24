package money.hejje.risk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.orders.IntentStatus;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderReason;
import money.hejje.risk.AccountSnapshot;
import money.hejje.risk.RiskLimits;
import org.junit.jupiter.api.Test;

class RiskControlsTest {

    static final UUID INSTR = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-09-08T05:00:00Z"); // 10:30 IST

    static RiskLimits limits() {
        return new RiskLimits(ExecutionMode.PAPER, Money.ofRupees(5000), Money.ofRupees(5000), Money.ofRupees(7500),
                Money.ofRupees(500000), new BigDecimal("80.00"), 5, Money.ofRupees(1000000), 20, Money.ofRupees(2000), 1000,
                Money.ofRupees(500000), new BigDecimal("1.00"), true, new BigDecimal("5.00"), LocalTime.of(14, 45), true, 10, 3);
    }

    static AccountSnapshot snapshot(Money realized, Money unrealized, int open, int trades, int consecutive, Map<UUID, Instant> lastTrade) {
        return new AccountSnapshot(realized, unrealized, open, Money.ZERO, trades, consecutive, Money.ofRupees(100000),
                Money.ofRupees(50000), Map.of(), lastTrade == null ? Map.of() : lastTrade);
    }

    static OrderIntent intent(Side side, int qty, Price limit, Price stop, Price target) {
        return new OrderIntent(UUID.randomUUID(), "k", UUID.randomUUID(), ActorType.USER, "t", null, null, INSTR, side,
                Quantity.of(qty), limit == null ? OrderType.MARKET : OrderType.LIMIT, Product.MIS, limit, null, stop, target, null,
                OrderReason.MANUAL, ExecutionMode.PAPER, IntentStatus.VALIDATING, List.of(), NOW);
    }

    static RiskInputs inputs(OrderIntent intent, AccountSnapshot snapshot, BigDecimal price) {
        return new RiskInputs(intent, snapshot, limits(), 1, price, null, false, true, true, LocalTime.of(10, 30), 0, false, false);
    }

    @Test
    void dailyLoss() {
        RiskInputs ok = inputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), null), snapshot(Money.ofRupees(-1000), Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"));
        assertThat(RiskControls.dailyLoss(ok).passed()).isTrue();
        RiskInputs bad = inputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), null), snapshot(Money.ofRupees(-5000), Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"));
        assertThat(RiskControls.dailyLoss(bad).passed()).isFalse();
    }

    @Test
    void openPositions() {
        assertThat(RiskControls.openPositions(inputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 4, 0, 0, null), BigDecimal.TEN)).passed()).isTrue();
        assertThat(RiskControls.openPositions(inputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 5, 0, 0, null), BigDecimal.TEN)).passed()).isFalse();
    }

    @Test
    void tradesPerDay() {
        assertThat(RiskControls.tradesPerDay(inputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 19, 0, null), BigDecimal.TEN)).passed()).isTrue();
        assertThat(RiskControls.tradesPerDay(inputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 20, 0, null), BigDecimal.TEN)).passed()).isFalse();
    }

    @Test
    void riskPerTrade() {
        // per-unit 2, qty 10 -> 20 < 2000 pass; per-unit 250, qty 10 -> 2500 > 2000 fail
        assertThat(RiskControls.riskPerTrade(inputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"))).passed()).isTrue();
        assertThat(RiskControls.riskPerTrade(inputs(intent(Side.BUY, 10, Price.of("1000.00"), Price.of("750.00"), null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("1000.00"))).passed()).isFalse();
    }

    @Test
    void quantityAndNotional() {
        assertThat(RiskControls.quantity(inputs(intent(Side.BUY, 1000, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), BigDecimal.TEN)).passed()).isTrue();
        assertThat(RiskControls.quantity(inputs(intent(Side.BUY, 1001, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), BigDecimal.TEN)).passed()).isFalse();
        assertThat(RiskControls.notional(inputs(intent(Side.BUY, 10, Price.of("100.00"), null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"))).passed()).isTrue();
        assertThat(RiskControls.notional(inputs(intent(Side.BUY, 100, Price.of("6000.00"), null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("6000.00"))).passed()).isFalse();
    }

    @Test
    void minRewardRiskAndMandatoryStop() {
        assertThat(RiskControls.minRewardRisk(inputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), Price.of("104.00")), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"))).passed()).isTrue();
        assertThat(RiskControls.minRewardRisk(inputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), Price.of("100.50")), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"))).passed()).isFalse();
        assertThat(RiskControls.mandatoryStop(inputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"))).passed()).isTrue();
        assertThat(RiskControls.mandatoryStop(inputs(intent(Side.BUY, 10, Price.of("100.00"), null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"))).passed()).isFalse();
    }

    @Test
    void maxStopDistanceAndTradingWindow() {
        assertThat(RiskControls.maxStopDistance(inputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"))).passed()).isTrue();
        assertThat(RiskControls.maxStopDistance(inputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("90.00"), null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), new BigDecimal("100.00"))).passed()).isFalse();
        RiskInputs afterCutoff = new RiskInputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), limits(), 1, new BigDecimal("100.00"), null, false, true, true, LocalTime.of(15, 0), 0, false, false);
        assertThat(RiskControls.tradingWindow(afterCutoff).passed()).isFalse();
    }

    @Test
    void consecutiveLossesAndReentry() {
        assertThat(RiskControls.consecutiveLosses(inputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 2, null), BigDecimal.TEN)).passed()).isTrue();
        assertThat(RiskControls.consecutiveLosses(inputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 3, null), BigDecimal.TEN)).passed()).isFalse();
        Map<UUID, Instant> recent = Map.of(INSTR, NOW.minusSeconds(120));
        assertThat(RiskControls.reentryCooldown(inputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, recent), BigDecimal.TEN), NOW).passed()).isFalse();
        Map<UUID, Instant> old = Map.of(INSTR, NOW.minusSeconds(20 * 60));
        assertThat(RiskControls.reentryCooldown(inputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, old), BigDecimal.TEN), NOW).passed()).isTrue();
    }

    static RiskLimits allowanceLimits(RiskLimits.TradesWhenGreen whenGreen) {
        RiskLimits l = limits();
        return new RiskLimits(l.mode(), l.maxLossPerDay(), l.maxRealizedLoss(), l.maxTotalLossInclUnrealized(), l.maxCapitalDeployed(),
                l.maxMarginUtilizationPct(), l.maxOpenPositions(), l.maxGrossExposure(), l.maxTradesPerDay(), l.maxRiskPerTrade(), l.maxQuantity(),
                l.maxNotional(), l.minRewardRisk(), l.mandatoryStop(), l.maxStopDistancePct(), l.noNewTradesAfter(), l.noAveragingDown(),
                l.noReentryMinutes(), l.maxConsecutiveLosses(), RiskLimits.LossStreakMode.ALLOWANCE, Money.ofRupees(500), 4, whenGreen);
    }

    static Instant at(String hhmm) {
        return java.time.LocalDateTime.parse("2026-09-08T" + hhmm).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
    }

    static AccountSnapshot day(List<AccountSnapshot.Close> closes, List<Instant> entries, Money net) {
        return new AccountSnapshot(net, Money.ZERO, 0, Money.ZERO, entries.size() * 2, 0, Money.ofRupees(100000), Money.ZERO, Map.of(), Map.of(), entries,
                closes);
    }

    static AccountSnapshot.Close close(String hhmm, String realized) {
        return new AccountSnapshot.Close(at(hhmm), new BigDecimal(realized));
    }

    @Test
    void allowanceModeLetsExactlyFourMoreEntriesThroughAfterThreeLosses() {
        List<AccountSnapshot.Close> closes = List.of(close("09:40", "-100"), close("09:55", "-120"), close("10:10", "-90")); // −310: under the drawdown
        List<Instant> before = List.of(at("09:30"), at("09:45"), at("10:00"));
        RiskLimits l = allowanceLimits(RiskLimits.TradesWhenGreen.LIMIT);
        for (int k = 0; k <= 4; k++) {
            List<Instant> entries = new java.util.ArrayList<>(before);
            for (int i = 0; i < k; i++) {
                entries.add(at("10:1" + (i + 5)));
            }
            RiskInputs in = new RiskInputs(intent(Side.BUY, 1, null, null, null), day(closes, entries, Money.ofRupees(-310)), l, 1, BigDecimal.TEN, null, false,
                    true, true, LocalTime.of(10, 30), 0, false, false);
            money.hejje.risk.RiskCheck c = RiskControls.lossStreak(in);
            if (k < 4) {
                assertThat(c.passed()).as("entry %d after the streak", k + 1).isTrue();
                assertThat(c.message()).contains(k + "/4");
            } else {
                assertThat(c.passed()).as("the fifth entry after the streak").isFalse();
                assertThat(c.name()).isEqualTo("LOSS_STREAK_ALLOWANCE");
                assertThat(c.message()).contains("3 consecutive losses");
            }
        }
        // a winning trade after the trigger does not give the allowance back
        List<AccountSnapshot.Close> withWin = new java.util.ArrayList<>(closes);
        withWin.add(close("10:40", "400"));
        List<Instant> four = List.of(at("09:30"), at("09:45"), at("10:00"), at("10:15"), at("10:16"), at("10:17"), at("10:18"));
        assertThat(RiskControls.lossStreak(new RiskInputs(intent(Side.BUY, 1, null, null, null), day(withWin, four, Money.ofRupees(90)), l, 1, BigDecimal.TEN,
                null, false, true, true, LocalTime.of(10, 45), 0, false, false)).passed()).isFalse();
        // BLOCK (the default) is the consecutive-loss limit unchanged
        RiskInputs block = inputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 3, null), BigDecimal.TEN);
        assertThat(RiskControls.lossStreak(block).passed()).isFalse();
        assertThat(RiskControls.lossStreak(block).name()).isEqualTo("consecutiveLosses");
    }

    @Test
    void theDaysDrawdownAlsoTriggersTheAllowance() {
        // a win, then two losses: no streak of three, but the day's net reaches −500
        List<AccountSnapshot.Close> closes = List.of(close("09:40", "100"), close("09:55", "-300"), close("10:10", "-300"));
        RiskControls.Allowance a = RiskControls.allowance(day(closes, List.of(at("09:30"), at("10:20")), Money.ofRupees(-500)),
                allowanceLimits(RiskLimits.TradesWhenGreen.LIMIT));
        assertThat(a).isNotNull();
        assertThat(a.used()).isEqualTo(1);
        assertThat(a.reason()).startsWith("the day's net at -500.00");
        assertThat(RiskControls.allowance(day(List.of(close("09:40", "-100")), List.of(), Money.ofRupees(-100)),
                allowanceLimits(RiskLimits.TradesWhenGreen.LIMIT))).isNull();
    }

    @Test
    void noTradesPerDayLimitWhileGreenWhenUnlimited() {
        RiskLimits unlimited = allowanceLimits(RiskLimits.TradesWhenGreen.UNLIMITED);
        RiskInputs green = new RiskInputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ofRupees(10), Money.ZERO, 0, 25, 0, null), unlimited, 1,
                BigDecimal.TEN, null, false, true, true, LocalTime.of(10, 30), 0, false, false);
        assertThat(RiskControls.tradesPerDay(green).passed()).isTrue();
        RiskInputs red = new RiskInputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ofRupees(-10), Money.ZERO, 0, 25, 0, null), unlimited, 1,
                BigDecimal.TEN, null, false, true, true, LocalTime.of(10, 30), 0, false, false);
        assertThat(RiskControls.tradesPerDay(red).passed()).isFalse();
    }

    @Test
    void killSwitchAndBrokerReadiness() {
        RiskInputs stop = new RiskInputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), limits(), 1, BigDecimal.TEN, null, true, true, true, LocalTime.of(10, 30), 0, false, false);
        assertThat(RiskControls.killSwitch(stop).passed()).isFalse();
        RiskInputs disconnected = new RiskInputs(intent(Side.BUY, 1, null, null, null), snapshot(Money.ZERO, Money.ZERO, 0, 0, 0, null), limits(), 1, BigDecimal.TEN, null, false, false, false, LocalTime.of(10, 30), 0, false, false);
        assertThat(RiskControls.brokerConnected(disconnected).passed()).isFalse();
        assertThat(RiskControls.readiness(disconnected).passed()).isFalse();
    }

    @Test
    void averagingDown() {
        RiskInputs losing = new RiskInputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), null), snapshot(Money.ZERO, Money.ZERO, 1, 0, 0, null), limits(), 1, new BigDecimal("100.00"), null, false, true, true, LocalTime.of(10, 30), 10, true, false);
        assertThat(RiskControls.averagingDown(losing).passed()).isFalse();
        RiskInputs winning = new RiskInputs(intent(Side.BUY, 10, Price.of("100.00"), Price.of("98.00"), null), snapshot(Money.ZERO, Money.ZERO, 1, 0, 0, null), limits(), 1, new BigDecimal("100.00"), null, false, true, true, LocalTime.of(10, 30), 10, false, false);
        assertThat(RiskControls.averagingDown(winning).passed()).isTrue();
    }
}
