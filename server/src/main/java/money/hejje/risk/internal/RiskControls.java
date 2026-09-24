package money.hejje.risk.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import money.hejje.common.Money;
import money.hejje.common.Price;
import money.hejje.orders.OrderIntent;
import money.hejje.risk.AccountSnapshot;
import money.hejje.risk.RiskCheck;
import money.hejje.risk.RiskLimits;

/** The individual pre-trade controls (PRD section 31). Each is a pure function returning a {@link RiskCheck}. */
public final class RiskControls {

    private RiskControls() {
    }

    // --- always-on (also apply to exposure-reducing intents) ---------------------------------------------------------

    public static RiskCheck brokerConnected(RiskInputs in) {
        return in.brokerConnected() ? RiskCheck.pass("brokerConnected", "broker connected")
                : RiskCheck.fail("brokerConnected", "DISCONNECTED", "CONNECTED", "broker is not connected");
    }

    public static RiskCheck readiness(RiskInputs in) {
        return in.executionEnabled() ? RiskCheck.pass("readiness", "execution enabled")
                : RiskCheck.fail("readiness", "BLOCKED", "READY", "execution is not enabled");
    }

    // --- limit checks (skipped for exposure-reducing intents) --------------------------------------------------------

    public static RiskCheck killSwitch(RiskInputs in) {
        return in.killSwitchStop() ? RiskCheck.fail("killSwitch", "STOP_NEW_ORDERS", "ARMED", "kill switch is stopping new orders")
                : RiskCheck.pass("killSwitch", "armed");
    }

    public static RiskCheck dailyLoss(RiskInputs in) {
        Money total = in.snapshot().totalPnl();
        Money limit = in.limits().maxLossPerDay();
        return total.negate().compareTo(limit) >= 0
                ? RiskCheck.fail("dailyLoss", total.toRupeesString(), "-" + limit.toRupeesString(), "daily loss limit reached")
                : RiskCheck.pass("dailyLoss", total.toRupeesString());
    }

    public static RiskCheck realizedLoss(RiskInputs in) {
        Money realized = in.snapshot().realizedPnl();
        Money limit = in.limits().maxRealizedLoss();
        return realized.negate().compareTo(limit) >= 0
                ? RiskCheck.fail("realizedLoss", realized.toRupeesString(), "-" + limit.toRupeesString(), "realized loss limit reached")
                : RiskCheck.pass("realizedLoss", realized.toRupeesString());
    }

    public static RiskCheck totalLoss(RiskInputs in) {
        Money total = in.snapshot().totalPnl();
        Money limit = in.limits().maxTotalLossInclUnrealized();
        return total.negate().compareTo(limit) >= 0
                ? RiskCheck.fail("totalLoss", total.toRupeesString(), "-" + limit.toRupeesString(), "total loss (incl. unrealized) limit reached")
                : RiskCheck.pass("totalLoss", total.toRupeesString());
    }

    public static RiskCheck openPositions(RiskInputs in) {
        boolean newInstrument = in.currentNet() == 0;
        int count = in.snapshot().openPositionCount();
        return newInstrument && count >= in.limits().maxOpenPositions()
                ? RiskCheck.fail("openPositions", String.valueOf(count), String.valueOf(in.limits().maxOpenPositions()), "max open positions reached")
                : RiskCheck.pass("openPositions", String.valueOf(count));
    }

    public static RiskCheck tradesPerDay(RiskInputs in) {
        int trades = in.snapshot().tradesToday();
        if (in.limits().tradesPerDayWhenGreen() == RiskLimits.TradesWhenGreen.UNLIMITED && in.snapshot().totalPnl().paise() >= 0) {
            return RiskCheck.pass("tradesPerDay", trades + " (no limit while the day is green)"); // plan M9.7
        }
        return trades >= in.limits().maxTradesPerDay()
                ? RiskCheck.fail("tradesPerDay", String.valueOf(trades), String.valueOf(in.limits().maxTradesPerDay()), "max trades per day reached")
                : RiskCheck.pass("tradesPerDay", String.valueOf(trades));
    }

    public static RiskCheck riskPerTrade(RiskInputs in) {
        OrderIntent intent = in.intent();
        if (intent.stopPrice() == null || in.referencePrice() == null) {
            return RiskCheck.pass("riskPerTrade", "no stop provided");
        }
        BigDecimal perUnit = in.referencePrice().subtract(intent.stopPrice().value()).abs();
        Money risk = Money.of(perUnit.multiply(BigDecimal.valueOf(intent.quantity().value())).setScale(2, RoundingMode.HALF_UP));
        return risk.compareTo(in.limits().maxRiskPerTrade()) > 0
                ? RiskCheck.fail("riskPerTrade", risk.toRupeesString(), in.limits().maxRiskPerTrade().toRupeesString(), "risk per trade exceeds limit")
                : RiskCheck.pass("riskPerTrade", risk.toRupeesString());
    }

    public static RiskCheck quantity(RiskInputs in) {
        int qty = in.intent().quantity().value();
        return qty > in.limits().maxQuantity()
                ? RiskCheck.fail("quantity", String.valueOf(qty), String.valueOf(in.limits().maxQuantity()), "quantity exceeds limit")
                : RiskCheck.pass("quantity", String.valueOf(qty));
    }

    public static RiskCheck notional(RiskInputs in) {
        if (in.referencePrice() == null) {
            return RiskCheck.pass("notional", "no price");
        }
        Money notional = Money.of(in.referencePrice().multiply(BigDecimal.valueOf(in.intent().quantity().value())).setScale(2, RoundingMode.HALF_UP));
        return notional.compareTo(in.limits().maxNotional()) > 0
                ? RiskCheck.fail("notional", notional.toRupeesString(), in.limits().maxNotional().toRupeesString(), "notional exceeds limit")
                : RiskCheck.pass("notional", notional.toRupeesString());
    }

    public static RiskCheck marginUtilization(RiskInputs in) {
        Money used = in.snapshot().usedMargin();
        Money available = in.snapshot().availableCash();
        Money add = in.estimatedMargin() != null ? in.estimatedMargin() : notionalMargin(in);
        long capital = used.paise() + available.paise();
        if (capital <= 0) {
            return RiskCheck.pass("marginUtilization", "no margin data");
        }
        BigDecimal pct = BigDecimal.valueOf(used.paise() + add.paise()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(capital), 2, RoundingMode.HALF_UP);
        return pct.compareTo(in.limits().maxMarginUtilizationPct()) > 0
                ? RiskCheck.fail("marginUtilization", pct.toPlainString() + "%", in.limits().maxMarginUtilizationPct().toPlainString() + "%", "margin utilization exceeds limit")
                : RiskCheck.pass("marginUtilization", pct.toPlainString() + "%");
    }

    private static Money notionalMargin(RiskInputs in) {
        if (in.referencePrice() == null) {
            return Money.ZERO;
        }
        return Money.of(in.referencePrice().multiply(BigDecimal.valueOf(in.intent().quantity().value())).setScale(2, RoundingMode.HALF_UP));
    }

    public static RiskCheck minRewardRisk(RiskInputs in) {
        OrderIntent intent = in.intent();
        if (intent.targetPrice() == null || intent.stopPrice() == null || in.referencePrice() == null) {
            return RiskCheck.pass("minRewardRisk", "no target/stop");
        }
        BigDecimal reward = intent.targetPrice().value().subtract(in.referencePrice()).abs();
        BigDecimal risk = in.referencePrice().subtract(intent.stopPrice().value()).abs();
        if (risk.signum() == 0) {
            return RiskCheck.fail("minRewardRisk", "0", in.limits().minRewardRisk().toPlainString(), "stop equals entry");
        }
        BigDecimal rr = reward.divide(risk, 2, RoundingMode.HALF_UP);
        return rr.compareTo(in.limits().minRewardRisk()) < 0
                ? RiskCheck.fail("minRewardRisk", rr.toPlainString(), in.limits().minRewardRisk().toPlainString(), "reward:risk below minimum")
                : RiskCheck.pass("minRewardRisk", rr.toPlainString());
    }

    public static RiskCheck mandatoryStop(RiskInputs in) {
        if (!in.limits().mandatoryStop()) {
            return RiskCheck.pass("mandatoryStop", "not required");
        }
        return in.intent().stopPrice() == null
                ? RiskCheck.fail("mandatoryStop", "no stop", "required", "a stop is mandatory for new positions")
                : RiskCheck.pass("mandatoryStop", "stop present");
    }

    public static RiskCheck maxStopDistance(RiskInputs in) {
        OrderIntent intent = in.intent();
        if (intent.stopPrice() == null || in.referencePrice() == null || in.referencePrice().signum() == 0) {
            return RiskCheck.pass("maxStopDistance", "no stop/price");
        }
        BigDecimal distPct = in.referencePrice().subtract(intent.stopPrice().value()).abs()
                .multiply(BigDecimal.valueOf(100)).divide(in.referencePrice(), 2, RoundingMode.HALF_UP);
        return distPct.compareTo(in.limits().maxStopDistancePct()) > 0
                ? RiskCheck.fail("maxStopDistance", distPct.toPlainString() + "%", in.limits().maxStopDistancePct().toPlainString() + "%", "stop is too far")
                : RiskCheck.pass("maxStopDistance", distPct.toPlainString() + "%");
    }

    public static RiskCheck tradingWindow(RiskInputs in) {
        return in.nowIst().isAfter(in.limits().noNewTradesAfter())
                ? RiskCheck.fail("tradingWindow", in.nowIst().toString(), in.limits().noNewTradesAfter().toString(), "no new trades after cutoff")
                : RiskCheck.pass("tradingWindow", in.nowIst().toString());
    }

    public static RiskCheck averagingDown(RiskInputs in) {
        if (!in.limits().noAveragingDown()) {
            return RiskCheck.pass("averagingDown", "allowed");
        }
        boolean sameDirection = in.currentNet() != 0
                && (in.currentNet() > 0) == (in.intent().side() == money.hejje.common.Side.BUY);
        return sameDirection && in.instrumentLosing()
                ? RiskCheck.fail("averagingDown", "adding to losing position", "disallowed", "averaging down is disabled")
                : RiskCheck.pass("averagingDown", "ok");
    }

    public static RiskCheck reentryCooldown(RiskInputs in, java.time.Instant now) {
        java.time.Instant last = in.snapshot().lastTradeAt().get(in.intent().instrumentId());
        if (last == null) {
            return RiskCheck.pass("reentryCooldown", "no prior trade");
        }
        long minutes = java.time.Duration.between(last, now).toMinutes();
        return minutes < in.limits().noReentryMinutes()
                ? RiskCheck.fail("reentryCooldown", minutes + "m", in.limits().noReentryMinutes() + "m", "re-entry cooldown active")
                : RiskCheck.pass("reentryCooldown", minutes + "m");
    }

    /**
     * Plan M9.7: BLOCK is the consecutive-loss limit; ALLOWANCE lets {@code lossStreakAllowance} more entries through from
     * the moment the streak (or the day's drawdown) triggers, and then rejects with LOSS_STREAK_ALLOWANCE. A winning
     * trade does not give the allowance back.
     */
    public static RiskCheck lossStreak(RiskInputs in) {
        if (in.limits().lossStreakMode() == RiskLimits.LossStreakMode.BLOCK) {
            return consecutiveLosses(in);
        }
        Allowance a = allowance(in.snapshot(), in.limits());
        if (a == null) {
            return RiskCheck.pass("lossStreakAllowance", "not triggered today");
        }
        String used = a.used() + "/" + a.allowance();
        return a.used() >= a.allowance()
                ? RiskCheck.fail("LOSS_STREAK_ALLOWANCE", used, "< " + a.allowance(), "loss-streak allowance used: " + a.reason())
                : RiskCheck.pass("lossStreakAllowance", used + " entries since " + a.reason());
    }

    /** An ALLOWANCE day's state: entries used since the trigger, the allowance, and why it triggered. Null when not triggered. */
    public record Allowance(int used, int allowance, java.time.Instant since, String reason) {}

    public static Allowance allowance(AccountSnapshot s, RiskLimits l) {
        int streak = 0;
        java.math.BigDecimal net = java.math.BigDecimal.ZERO;
        java.math.BigDecimal drawdown = l.allowanceDrawdown().toRupees();
        for (AccountSnapshot.Close c : s.closesToday()) {
            net = net.add(c.realized());
            streak = c.realized().signum() < 0 ? streak + 1 : 0;
            String reason = null;
            if (streak >= l.maxConsecutiveLosses()) {
                reason = streak + " consecutive losses";
            } else if (drawdown.signum() > 0 && net.compareTo(drawdown.negate()) <= 0) {
                reason = "the day's net at " + net.setScale(2, RoundingMode.HALF_UP).toPlainString();
            }
            if (reason != null) {
                java.time.Instant since = c.at();
                int used = (int) s.entriesToday().stream().filter(e -> e.isAfter(since)).count();
                return new Allowance(used, l.lossStreakAllowance(), since, reason);
            }
        }
        return null;
    }

    public static RiskCheck consecutiveLosses(RiskInputs in) {
        int losses = in.snapshot().consecutiveLosses();
        return losses >= in.limits().maxConsecutiveLosses()
                ? RiskCheck.fail("consecutiveLosses", String.valueOf(losses), String.valueOf(in.limits().maxConsecutiveLosses()), "consecutive loss limit reached")
                : RiskCheck.pass("consecutiveLosses", String.valueOf(losses));
    }
}
