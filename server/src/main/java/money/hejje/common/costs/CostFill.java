package money.hejje.common.costs;

import java.math.BigDecimal;
import money.hejje.common.InstrumentType;
import money.hejje.common.Product;
import money.hejje.common.Side;

/** One executed fill to price. Turnover is {@code price * quantity} (premium turnover for options). */
public record CostFill(InstrumentType instrumentType, Product product, Side side, int quantity, BigDecimal price) {

    public Segment segment() {
        return Segment.of(instrumentType, product);
    }

    public BigDecimal turnover() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
