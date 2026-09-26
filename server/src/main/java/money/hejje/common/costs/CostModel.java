package money.hejje.common.costs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import money.hejje.common.Money;
import money.hejje.common.Side;
import org.springframework.stereotype.Component;

/** Deterministic transaction cost calculator (PRD section 12.2). Used by paper fills and, later, the backtester. */
@Component
public class CostModel {

    private final CostProperties properties;
    private final BigDecimal dpCharge;

    /** Without the depository charge (tests and callers that price single fills). */
    public CostModel(CostProperties properties) {
        this(properties, BigDecimal.ZERO);
    }

    /** @param dpCharge rupees per scrip and sell day for a delivery sell, GST included ({@code hejje.costs.dp-charge}) */
    @org.springframework.beans.factory.annotation.Autowired
    public CostModel(CostProperties properties, @org.springframework.beans.factory.annotation.Value("${hejje.costs.dp-charge:15.34}") BigDecimal dpCharge) {
        this.properties = properties;
        this.dpCharge = dpCharge;
    }

    public CostBreakdown compute(CostFill fill) {
        Segment segment = fill.segment();
        CostProperties.Segments rates = properties.forSegment(segment);
        BigDecimal turnover = fill.turnover();

        // Zerodha: options pay the flat fee per executed order; other segments the lower of the flat fee and the percentage
        BigDecimal brokerage = rates.brokerageFree() ? BigDecimal.ZERO
                : segment == Segment.OPTIONS ? properties.brokerageFlat()
                : properties.brokerageFlat().min(turnover.multiply(properties.brokeragePct()));

        boolean chargeStt = rates.sttOnSellOnly() ? fill.side() == Side.SELL : true;
        BigDecimal stt = chargeStt ? turnover.multiply(rates.sttPct()) : BigDecimal.ZERO;

        BigDecimal exchangeTxn = turnover.multiply(rates.exchangeTxnPct());
        BigDecimal sebi = turnover.multiply(properties.sebiPct());
        BigDecimal gst = brokerage.add(exchangeTxn).add(sebi).multiply(properties.gstPct());
        BigDecimal stampDuty = fill.side() == Side.BUY ? turnover.multiply(rates.stampDutyPct()) : BigDecimal.ZERO;

        // depository (DP) charge: a delivery sell debits the demat account once per scrip and day (plan M11.1)
        BigDecimal dp = segment == Segment.EQUITY_DELIVERY && fill.side() == Side.SELL && fill.dpCharge() ? dpCharge : BigDecimal.ZERO;

        return CostBreakdown.of(money(brokerage), money(stt), money(exchangeTxn), money(gst), money(sebi), money(stampDuty), money(dp));
    }

    private static Money money(BigDecimal rupees) {
        return Money.of(rupees.setScale(2, RoundingMode.HALF_UP));
    }
}
