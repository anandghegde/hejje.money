package money.hejje.common.costs;

import money.hejje.common.InstrumentType;
import money.hejje.common.Product;

/** Charge segment: the tax/fee schedule depends on it. */
public enum Segment {
    EQUITY_INTRADAY,
    EQUITY_DELIVERY,
    FUTURES,
    OPTIONS;

    public static Segment of(InstrumentType type, Product product) {
        return switch (type) {
            case OPT -> OPTIONS;
            case FUT -> FUTURES;
            case EQ, INDEX -> product == Product.CNC ? EQUITY_DELIVERY : EQUITY_INTRADAY;
        };
    }
}
