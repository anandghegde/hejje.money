package money.hejje.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.risk.RiskCheck;

/**
 * Overnight risk of the swing book (plan M11.3), pure. A position's risk is its stop distance plus a gap allowance:
 * {@code quantity × (max(0, price − stop) + gap% × price)}, because an opening gap can jump the stop; a position without a
 * known stop risks its whole value. The book's overnight risk is the sum over its open positions at their last price.
 */
public final class SwingRisk {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SwingRisk() {
    }

    /** One open swing position: the price is the last price (the average when there is none); stop null when unknown. */
    public record Holding(UUID instrumentId, String symbol, int quantity, BigDecimal averagePrice, BigDecimal price, BigDecimal stop, String industry) {

        public Money cost() {
            return money(averagePrice.multiply(BigDecimal.valueOf(quantity)));
        }
    }

    /** A new delivery entry: quantity at price with its stop. */
    public record Entry(UUID instrumentId, int quantity, BigDecimal price, BigDecimal stop, String industry) {}

    /**
     * What else the checks need: the mode (PAPER and SIM only), the unprotected positions (M11.2), the macro event on the next
     * session (null when none) and the stock's surveillance flag (null when unknown).
     */
    public record Context(ExecutionMode mode, List<String> unprotected, String eventNextSession, String surveillanceFlag) {}

    /** Gap-adjusted risk of {@code quantity} at {@code price} with {@code stop}. */
    public static Money risk(int quantity, BigDecimal price, BigDecimal stop, BigDecimal gapPct) {
        BigDecimal perShare = stop == null ? price : price.subtract(stop).max(BigDecimal.ZERO).add(gap(price, gapPct));
        return money(perShare.multiply(BigDecimal.valueOf(quantity)));
    }

    public static Money risk(Holding h, BigDecimal gapPct) {
        return risk(h.quantity(), h.price(), h.stop(), gapPct);
    }

    public static Money overnightRisk(List<Holding> book, BigDecimal gapPct) {
        Money total = Money.ZERO;
        for (Holding h : book) {
            total = total.plus(risk(h, gapPct));
        }
        return total;
    }

    /** Every swing limit against a new entry; each failure names its limit. */
    public static List<RiskCheck> check(SwingLimits limits, List<Holding> book, Entry entry, Context ctx) {
        List<RiskCheck> checks = new ArrayList<>();
        checks.add(ctx.mode() == ExecutionMode.PAPER || ctx.mode() == ExecutionMode.SIM
                ? RiskCheck.pass("swingPaperOnly", ctx.mode().name())
                : RiskCheck.fail("swingPaperOnly", ctx.mode().name(), "PAPER", "swing trading is PAPER-only until the H5 validation passes and LIVE is decided (plan Phase 11)"));
        checks.add(ctx.unprotected().isEmpty() ? RiskCheck.pass("swingProtection", "every delivery position has its GTT")
                : RiskCheck.fail("swingProtection", ctx.unprotected().size() + " unprotected", "0",
                        "new swing entries are blocked: no confirmed GTT at the broker for " + String.join(", ", ctx.unprotected())));
        if (entry.price() == null) {
            checks.add(RiskCheck.fail("swingPrice", "none", "a price", "no price to measure the entry's risk; place a LIMIT or wait for a quote"));
            return checks;
        }
        if (entry.stop() == null || entry.stop().compareTo(entry.price()) >= 0) {
            checks.add(RiskCheck.fail("swingStop", entry.stop() == null ? "no stop" : entry.stop().toPlainString(), "below " + entry.price().toPlainString(),
                    "a swing entry needs a stop below its price: the stop becomes the GTT at the broker"));
            return checks;
        }
        checks.add(RiskCheck.pass("swingStop", entry.stop().toPlainString()));
        BigDecimal gapPct = limits.gapAllowancePct();
        List<Holding> same = book.stream().filter(h -> h.instrumentId().equals(entry.instrumentId())).toList();
        boolean newPosition = same.isEmpty();

        int open = book.size();
        checks.add(newPosition && open >= limits.maxOpenPositions()
                ? RiskCheck.fail("swingOpenPositions", String.valueOf(open), String.valueOf(limits.maxOpenPositions()), "max open swing positions reached")
                : RiskCheck.pass("swingOpenPositions", String.valueOf(open)));

        Money entryRisk = risk(entry.quantity(), entry.price(), entry.stop(), gapPct);
        Money positionRisk = entryRisk;
        for (Holding h : same) {
            positionRisk = positionRisk.plus(risk(h, gapPct));
        }
        checks.add(positionRisk.compareTo(limits.maxRiskPerPosition()) > 0
                ? RiskCheck.fail("swingRiskPerPosition", positionRisk.toRupeesString(), limits.maxRiskPerPosition().toRupeesString(),
                        "risk per position (stop distance + " + gapPct.stripTrailingZeros().toPlainString() + "% gap allowance) exceeds the limit")
                : RiskCheck.pass("swingRiskPerPosition", positionRisk.toRupeesString()));

        Money overnight = overnightRisk(book, gapPct).plus(entryRisk);
        checks.add(overnight.compareTo(limits.maxOvernightRisk()) > 0
                ? RiskCheck.fail("swingOvernightRisk", overnight.toRupeesString(), limits.maxOvernightRisk().toRupeesString(),
                        "the swing book's gap-adjusted overnight risk would exceed its budget")
                : RiskCheck.pass("swingOvernightRisk", overnight.toRupeesString()));

        Money deployed = money(entry.price().multiply(BigDecimal.valueOf(entry.quantity())));
        for (Holding h : book) {
            deployed = deployed.plus(h.cost());
        }
        checks.add(deployed.compareTo(limits.swingCapital()) > 0
                ? RiskCheck.fail("swingCapital", deployed.toRupeesString(), limits.swingCapital().toRupeesString(), "the swing book would exceed its capital")
                : RiskCheck.pass("swingCapital", deployed.toRupeesString()));

        if (entry.industry() == null || entry.industry().isBlank()) {
            checks.add(RiskCheck.pass("swingIndustry", "industry unknown (not in the swing universe)"));
        } else {
            long inIndustry = book.stream().filter(h -> entry.industry().equals(h.industry()) && !h.instrumentId().equals(entry.instrumentId())).count();
            checks.add(newPosition && inIndustry >= limits.maxPositionsPerIndustry()
                    ? RiskCheck.fail("swingIndustry", inIndustry + " in " + entry.industry(), String.valueOf(limits.maxPositionsPerIndustry()),
                            "max open swing positions in one industry reached")
                    : RiskCheck.pass("swingIndustry", inIndustry + " in " + entry.industry()));
        }

        checks.add(limits.blockBeforeEvents() && ctx.eventNextSession() != null
                ? RiskCheck.fail("swingEventNextSession", ctx.eventNextSession(), "none", "no new swing entries on the session before " + ctx.eventNextSession())
                : RiskCheck.pass("swingEventNextSession", ctx.eventNextSession() == null ? "none" : ctx.eventNextSession() + " (not blocking)"));

        String flag = ctx.surveillanceFlag();
        boolean flagged = flag != null && !"NONE".equals(flag);
        checks.add(limits.blockSurveillance() && flagged
                ? RiskCheck.fail("swingSurveillance", flag, "NONE", "no swing entry in a stock under NSE surveillance (" + flag + ")")
                : RiskCheck.pass("swingSurveillance", flag == null ? "no surveillance data" : flag));
        return checks;
    }

    /** The largest entry the limits allow and the limit that binds it. */
    public record Size(int quantity, BigDecimal riskPerShare, Money riskBudget, String limitedBy) {}

    /**
     * Sizing from the risk budget (plan M11.3): the gap-adjusted risk per share is {@code entry − stop + gap% × entry}; the
     * quantity is the smallest of the risk-per-position budget, what is left of the overnight budget, and what is left of
     * the swing capital. Zero when the entry cannot be sized (no room, or a stop at or above the entry).
     */
    public static Size size(SwingLimits limits, List<Holding> book, BigDecimal entry, BigDecimal stop) {
        if (stop == null || entry == null || stop.compareTo(entry) >= 0) {
            return new Size(0, null, Money.ZERO, "stop");
        }
        BigDecimal perShare = entry.subtract(stop).add(gap(entry, limits.gapAllowancePct()));
        Money overnightLeft = limits.maxOvernightRisk().minus(overnightRisk(book, limits.gapAllowancePct()));
        Money capitalLeft = limits.swingCapital();
        for (Holding h : book) {
            capitalLeft = capitalLeft.minus(h.cost());
        }
        int byPosition = shares(limits.maxRiskPerPosition().toRupees(), perShare);
        int byOvernight = shares(overnightLeft.toRupees(), perShare);
        int byCapital = shares(capitalLeft.toRupees(), entry);
        int qty = Math.min(byPosition, Math.min(byOvernight, byCapital));
        String limitedBy = qty == byPosition ? "riskPerPosition" : qty == byOvernight ? "overnightRisk" : "capital";
        Money budget = limits.maxRiskPerPosition().compareTo(overnightLeft) < 0 ? limits.maxRiskPerPosition() : overnightLeft;
        return new Size(Math.max(0, qty), perShare.setScale(2, RoundingMode.HALF_UP), budget, limitedBy);
    }

    private static int shares(BigDecimal rupees, BigDecimal perShare) {
        if (rupees.signum() <= 0 || perShare.signum() <= 0) {
            return 0;
        }
        return rupees.divide(perShare, 0, RoundingMode.FLOOR).intValue();
    }

    private static BigDecimal gap(BigDecimal price, BigDecimal gapPct) {
        return price.multiply(gapPct).divide(HUNDRED, 8, RoundingMode.HALF_UP);
    }

    private static Money money(BigDecimal rupees) {
        return Money.of(rupees.setScale(2, RoundingMode.HALF_UP));
    }
}
