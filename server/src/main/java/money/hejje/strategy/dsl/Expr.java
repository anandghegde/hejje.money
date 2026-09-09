package money.hejje.strategy.dsl;

import java.time.Duration;
import java.util.List;

/** Expression AST. {@link #text()} renders the canonical form (used for hashing, evidence and golden tests). */
public sealed interface Expr {

    String text();

    /** A numeric literal such as {@code 1.4}. */
    record NumberLiteral(double value) implements Expr {
        @Override
        public String text() {
            return Numbers.format(value);
        }
    }

    /** A price/volume series reference with a bar offset: {@code close}, {@code close[1]}. */
    record SeriesRef(String name, int offset) implements Expr {
        public static final List<String> NAMES = List.of("open", "high", "low", "close", "volume");

        @Override
        public String text() {
            return offset == 0 ? name : name + "[" + offset + "]";
        }
    }

    /** An indicator call with its arguments and a bar offset: {@code ema(20)}, {@code rsi(14)[1]}, {@code vwap}. */
    record IndicatorCall(String name, List<Arg> args, int offset) implements Expr {
        public IndicatorCall {
            args = List.copyOf(args);
        }

        @Override
        public String text() {
            StringBuilder sb = new StringBuilder(name);
            if (!args.isEmpty()) {
                sb.append('(');
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(args.get(i).text());
                }
                sb.append(')');
            }
            if (offset != 0) {
                sb.append('[').append(offset).append(']');
            }
            return sb.toString();
        }
    }

    /** {@code left op right} for {@code + - * /}. */
    record Binary(ArithOp op, Expr left, Expr right) implements Expr {
        @Override
        public String text() {
            return "(" + left.text() + " " + op.symbol() + " " + right.text() + ")";
        }
    }

    /** Unary minus. */
    record Negate(Expr operand) implements Expr {
        @Override
        public String text() {
            return "-" + operand.text();
        }
    }

    /** An indicator argument: a number ({@code 20}, {@code 2.5}) or a duration ({@code 15m}, {@code 1h}). */
    sealed interface Arg {
        String text();

        record Number(double value) implements Arg {
            @Override
            public String text() {
                return Numbers.format(value);
            }
        }

        record DurationArg(Duration value) implements Arg {
            @Override
            public String text() {
                long minutes = value.toMinutes();
                if (minutes % (24 * 60) == 0) {
                    return (minutes / (24 * 60)) + "d";
                }
                if (minutes % 60 == 0) {
                    return (minutes / 60) + "h";
                }
                return minutes + "m";
            }
        }
    }

    enum ArithOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/");

        private final String symbol;

        ArithOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }
}
