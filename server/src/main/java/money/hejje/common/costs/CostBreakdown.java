package money.hejje.common.costs;

import money.hejje.common.Money;

/** Itemized transaction costs for one executed order (PRD section 12.2). All amounts in {@link Money} (paise). */
public record CostBreakdown(Money brokerage, Money stt, Money exchangeTxn, Money gst, Money sebi, Money stampDuty, Money total) {

    public static CostBreakdown of(Money brokerage, Money stt, Money exchangeTxn, Money gst, Money sebi, Money stampDuty) {
        Money total = brokerage.plus(stt).plus(exchangeTxn).plus(gst).plus(sebi).plus(stampDuty);
        return new CostBreakdown(brokerage, stt, exchangeTxn, gst, sebi, stampDuty, total);
    }
}
