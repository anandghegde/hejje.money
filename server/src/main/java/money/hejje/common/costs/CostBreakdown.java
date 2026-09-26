package money.hejje.common.costs;

import money.hejje.common.Money;

/**
 * Itemized transaction costs for one executed order (PRD section 12.2). All amounts in {@link Money} (paise).
 * {@code dpCharges} is the depository charge of a delivery sell (plan M11.1), GST included; zero otherwise.
 */
public record CostBreakdown(Money brokerage, Money stt, Money exchangeTxn, Money gst, Money sebi, Money stampDuty, Money dpCharges, Money total) {

    public static CostBreakdown of(Money brokerage, Money stt, Money exchangeTxn, Money gst, Money sebi, Money stampDuty) {
        return of(brokerage, stt, exchangeTxn, gst, sebi, stampDuty, Money.ZERO);
    }

    public static CostBreakdown of(Money brokerage, Money stt, Money exchangeTxn, Money gst, Money sebi, Money stampDuty, Money dpCharges) {
        Money total = brokerage.plus(stt).plus(exchangeTxn).plus(gst).plus(sebi).plus(stampDuty).plus(dpCharges);
        return new CostBreakdown(brokerage, stt, exchangeTxn, gst, sebi, stampDuty, dpCharges, total);
    }
}
