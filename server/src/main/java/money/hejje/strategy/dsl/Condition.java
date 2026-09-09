package money.hejje.strategy.dsl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** One parsed condition: {@code <expr> <op> <expr>}. Serializes to JSON as its canonical text. */
public record Condition(Expr lhs, CompareOp op, Expr rhs) {

    @JsonCreator
    public static Condition parse(String text) {
        return ConditionParser.parse(text);
    }

    @JsonValue
    public String text() {
        return lhs.text() + " " + op.symbol() + " " + rhs.text();
    }

    @Override
    public String toString() {
        return text();
    }
}
