package money.hejje.analytics;

import money.hejje.common.Product;

/** The P&L horizon of a trade (plan M11.1): delivery (CNC) trades are the swing book, everything else is intraday. */
public enum Horizon {
    INTRADAY, SWING;

    public static Horizon of(Product product) {
        return product == Product.CNC ? SWING : INTRADAY;
    }
}
