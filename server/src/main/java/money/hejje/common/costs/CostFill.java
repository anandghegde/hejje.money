package money.hejje.common.costs;

import java.math.BigDecimal;
import money.hejje.common.InstrumentType;
import money.hejje.common.Product;
import money.hejje.common.Side;

/**
 * One executed fill to price. Turnover is {@code price * quantity} (premium turnover for options). {@code dpCharge}: a
 * delivery sell pays the depository charge (once per scrip and day, plan M11.1); false for a later sell of the same
 * scrip on the same day.
 */
public record CostFill(InstrumentType instrumentType, Product product, Side side, int quantity, BigDecimal price, boolean dpCharge) {

    public CostFill(InstrumentType instrumentType, Product product, Side side, int quantity, BigDecimal price) {
        this(instrumentType, product, side, quantity, price, true);
    }

    public Segment segment() {
        return Segment.of(instrumentType, product);
    }

    public BigDecimal turnover() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
