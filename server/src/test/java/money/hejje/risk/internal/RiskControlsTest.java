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
