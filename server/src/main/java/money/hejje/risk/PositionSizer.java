package money.hejje.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import money.hejje.common.Money;
import money.hejje.common.Price;

/** Risk-based position sizing: quantity = floor((riskMoney / |entry - stop|) to a whole number of lots), capped at maxQty. */
public final class PositionSizer {

    private PositionSizer() {
    }

    /**
     * @param entry     intended entry price
     * @param stop      stop price (must differ from entry)
     * @param riskMoney money to risk on the trade
     * @param lotSize   instrument lot size
     * @param maxQty    hard quantity cap (0 = no cap)
     * @return quantity as a whole number of lots (may be 0 when the risk budget is smaller than one lot)
     */
    public static int size(Price entry, Price stop, Money riskMoney, int lotSize, int maxQty) {
        if (lotSize <= 0) {
            throw new IllegalArgumentException("Lot size must be positive");
        }
        BigDecimal perUnitRisk = entry.value().subtract(stop.value()).abs();
        if (perUnitRisk.signum() <= 0) {
            throw new IllegalArgumentException("Entry and stop must differ");
        }
        BigDecimal riskRupees = riskMoney.toRupees();
        int rawUnits = riskRupees.divide(perUnitRisk, 0, RoundingMode.DOWN).intValueExact();
        int lots = rawUnits / lotSize;
        int qty = lots * lotSize;
        if (maxQty > 0) {
            int maxLots = maxQty / lotSize;
            qty = Math.min(qty, maxLots * lotSize);
        }
        return Math.max(0, qty);
    }
}
