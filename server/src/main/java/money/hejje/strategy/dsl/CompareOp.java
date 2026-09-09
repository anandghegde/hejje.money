package money.hejje.strategy.dsl;

/** Comparison operators of a condition. */
public enum CompareOp {
    GT(">"), LT("<"), GE(">="), LE("<="), EQ("=="), CROSSES_ABOVE("crosses_above"), CROSSES_BELOW("crosses_below");

    private final String symbol;

    CompareOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public boolean isCross() {
        return this == CROSSES_ABOVE || this == CROSSES_BELOW;
    }

    static CompareOp fromSymbol(String symbol) {
        for (CompareOp op : values()) {
            if (op.symbol.equals(symbol)) {
                return op;
            }
        }
        return null;
    }
}
