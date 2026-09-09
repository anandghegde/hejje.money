package money.hejje.strategy.dsl;

import java.util.List;
import java.util.OptionalDouble;
import money.hejje.strategy.dsl.Expr.Binary;
import money.hejje.strategy.dsl.Expr.IndicatorCall;
import money.hejje.strategy.dsl.Expr.Negate;
import money.hejje.strategy.dsl.Expr.NumberLiteral;
import money.hejje.strategy.dsl.Expr.SeriesRef;

/**
 * Deterministic evaluator over a {@link BarContext}. Cross operators compare the current bar with the previous one:
 * {@code a crosses_above b} is {@code a[0] > b[0] && a[1] <= b[1]}. Any missing operand makes the result NOT_READY.
 * Division by zero is NOT_READY too (never a silent pass).
 */
public final class ConditionEvaluator {

    private static final double EPSILON = 1e-9;

    private ConditionEvaluator() {
    }

    public static EvalResult evaluate(Condition condition, BarContext ctx) {
        OptionalDouble lhs = evaluate(condition.lhs(), ctx, 0);
        OptionalDouble rhs = evaluate(condition.rhs(), ctx, 0);
        if (lhs.isEmpty() || rhs.isEmpty()) {
            return EvalResult.notReady(condition);
        }
        double l = lhs.getAsDouble();
        double r = rhs.getAsDouble();
        boolean passed;
        switch (condition.op()) {
            case GT -> passed = l > r;
            case LT -> passed = l < r;
            case GE -> passed = l >= r;
            case LE -> passed = l <= r;
            case EQ -> passed = Math.abs(l - r) <= EPSILON;
            case CROSSES_ABOVE, CROSSES_BELOW -> {
                OptionalDouble pl = evaluate(condition.lhs(), ctx, 1);
                OptionalDouble pr = evaluate(condition.rhs(), ctx, 1);
                if (pl.isEmpty() || pr.isEmpty()) {
                    return EvalResult.notReady(condition);
                }
                passed = condition.op() == CompareOp.CROSSES_ABOVE
                        ? l > r && pl.getAsDouble() <= pr.getAsDouble()
                        : l < r && pl.getAsDouble() >= pr.getAsDouble();
            }
            default -> throw new IllegalStateException("Unhandled operator " + condition.op());
        }
        return new EvalResult(condition.text(), passed ? EvalStatus.PASSED : EvalStatus.FAILED, l, r);
    }

    /** Evaluates an expression shifted {@code shift} bars into the past (0 = current closed bar). */
    public static OptionalDouble evaluate(Expr expr, BarContext ctx, int shift) {
        return switch (expr) {
            case NumberLiteral n -> OptionalDouble.of(n.value());
            case SeriesRef s -> ctx.series(s.name(), s.offset() + shift);
            case IndicatorCall c -> ctx.indicator(c.name(), c.args(), c.offset() + shift);
            case Negate n -> {
                OptionalDouble v = evaluate(n.operand(), ctx, shift);
                yield v.isEmpty() ? v : OptionalDouble.of(-v.getAsDouble());
            }
            case Binary b -> {
                OptionalDouble l = evaluate(b.left(), ctx, shift);
                OptionalDouble r = evaluate(b.right(), ctx, shift);
                if (l.isEmpty() || r.isEmpty()) {
                    yield OptionalDouble.empty();
                }
                double lv = l.getAsDouble();
                double rv = r.getAsDouble();
                yield switch (b.op()) {
                    case ADD -> OptionalDouble.of(lv + rv);
                    case SUB -> OptionalDouble.of(lv - rv);
                    case MUL -> OptionalDouble.of(lv * rv);
                    case DIV -> rv == 0 ? OptionalDouble.empty() : OptionalDouble.of(lv / rv);
                };
            }
        };
    }

    /** Evaluates every condition; the group passes when all (or any) pass. NOT_READY never counts as a pass. */
    public static List<EvalResult> evaluateAll(List<Condition> conditions, BarContext ctx) {
        return conditions.stream().map(c -> evaluate(c, ctx)).toList();
    }
}
