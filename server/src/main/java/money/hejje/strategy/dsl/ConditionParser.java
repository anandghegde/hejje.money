package money.hejje.strategy.dsl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import money.hejje.strategy.dsl.Expr.Arg;
import money.hejje.strategy.dsl.Expr.ArithOp;
import money.hejje.strategy.dsl.Expr.Binary;
import money.hejje.strategy.dsl.Expr.IndicatorCall;
import money.hejje.strategy.dsl.Expr.Negate;
import money.hejje.strategy.dsl.Expr.NumberLiteral;
import money.hejje.strategy.dsl.Expr.SeriesRef;

/**
 * Hand-written recursive-descent parser for the condition grammar (docs/strategy-dsl.md):
 * <pre>
 *   condition := expr compare expr
 *   compare   := '>' | '<' | '>=' | '<=' | '==' | 'crosses_above' | 'crosses_below'
 *   expr      := term (('+' | '-') term)*
 *   term      := factor (('*' | '/') factor)*
 *   factor    := number | ref | '(' expr ')' | '-' factor
 *   ref       := ident [ '(' args ')' ] [ '[' int ']' ]
 *   args      := arg (',' arg)*        arg := number | duration
 * </pre>
 * Indicator calls are normalised against the {@link IndicatorCatalog} (defaults filled in, arity checked) so that the
 * canonical text of a parsed condition is stable.
 */
public final class ConditionParser {

    private final List<Token> tokens;
    private final String source;
    private int index;

    private ConditionParser(String source) {
        this.source = source;
        this.tokens = Tokenizer.tokenize(source);
    }

    public static Condition parse(String text) {
        if (text == null || text.isBlank()) {
            throw new ParseException("Empty condition", 0);
        }
        ConditionParser parser = new ConditionParser(text.trim());
        Condition condition = parser.condition();
        if (parser.peek().kind() != TokenKind.EOF) {
            throw parser.error("Unexpected '" + parser.peek().text() + "'");
        }
        return condition;
    }

    /** Parses an expression alone (no comparison), used by tests and the validator. */
    public static Expr parseExpr(String text) {
        ConditionParser parser = new ConditionParser(text.trim());
        Expr expr = parser.expr();
        if (parser.peek().kind() != TokenKind.EOF) {
            throw parser.error("Unexpected '" + parser.peek().text() + "'");
        }
        return expr;
    }

    private Condition condition() {
        Expr lhs = expr();
        Token op = peek();
        CompareOp compare = switch (op.kind()) {
            case COMPARE -> CompareOp.fromSymbol(op.text());
            case IDENT -> CompareOp.fromSymbol(op.text());
            default -> null;
        };
        if (compare == null) {
            throw error(op.kind() == TokenKind.EOF ? "Expected a comparison operator" : "Expected a comparison operator but found '" + op.text() + "'");
        }
        next();
        Expr rhs = expr();
        return new Condition(lhs, compare, rhs);
    }

    private Expr expr() {
        Expr left = term();
        while (peek().kind() == TokenKind.OP && (peek().text().equals("+") || peek().text().equals("-"))) {
            ArithOp op = next().text().equals("+") ? ArithOp.ADD : ArithOp.SUB;
            left = new Binary(op, left, term());
        }
        return left;
    }

    private Expr term() {
        Expr left = factor();
        while (peek().kind() == TokenKind.OP && (peek().text().equals("*") || peek().text().equals("/"))) {
            ArithOp op = next().text().equals("*") ? ArithOp.MUL : ArithOp.DIV;
            left = new Binary(op, left, factor());
        }
        return left;
    }

    private Expr factor() {
        Token token = peek();
        switch (token.kind()) {
            case NUMBER -> {
                next();
                return new NumberLiteral(Double.parseDouble(token.text()));
            }
            case DURATION -> throw error("A duration is only valid as an indicator argument");
            case IDENT -> {
                next();
                return ref(token);
            }
            case OP -> {
                if (token.text().equals("(")) {
                    next();
                    Expr inner = expr();
                    expect(")");
                    return inner;
                }
                if (token.text().equals("-")) {
                    next();
                    return new Negate(factor());
                }
                throw error("Unexpected '" + token.text() + "'");
            }
            case COMPARE -> throw error("Unexpected '" + token.text() + "'");
            default -> throw error("Unexpected end of condition");
        }
    }

    private Expr ref(Token ident) {
        String name = ident.text();
        if (CompareOp.fromSymbol(name) != null) {
            throw new ParseException("Unexpected '" + name + "'", ident.position());
        }
        List<Arg> args = null;
        if (peek().kind() == TokenKind.OP && peek().text().equals("(")) {
            next();
            args = new ArrayList<>();
            if (!(peek().kind() == TokenKind.OP && peek().text().equals(")"))) {
                args.add(arg());
                while (peek().kind() == TokenKind.OP && peek().text().equals(",")) {
                    next();
                    args.add(arg());
                }
            }
            expect(")");
        }
        int offset = 0;
        if (peek().kind() == TokenKind.OP && peek().text().equals("[")) {
            next();
            Token n = peek();
            if (n.kind() != TokenKind.NUMBER || n.text().contains(".")) {
                throw error("Bar offset must be a whole number");
            }
            next();
            offset = Integer.parseInt(n.text());
            expect("]");
        }
        if (SeriesRef.NAMES.contains(name)) {
            if (args != null) {
                throw new ParseException("'" + name + "' is a series and takes no arguments", ident.position());
            }
            return new SeriesRef(name, offset);
        }
        IndicatorCall call = new IndicatorCall(name, args == null ? List.of() : args, offset);
        try {
            return IndicatorCatalog.normalize(call);
        } catch (IllegalArgumentException e) {
            throw new ParseException(e.getMessage(), ident.position());
        }
    }

    private Arg arg() {
        Token token = peek();
        return switch (token.kind()) {
            case NUMBER -> {
                next();
                yield new Arg.Number(Double.parseDouble(token.text()));
            }
            case DURATION -> {
                next();
                yield new Arg.DurationArg(duration(token.text()));
            }
            default -> throw error("Expected a number or duration argument");
        };
    }

    private static Duration duration(String text) {
        long n = Long.parseLong(text.substring(0, text.length() - 1));
        return switch (text.charAt(text.length() - 1)) {
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("Bad duration " + text);
        };
    }

    private void expect(String symbol) {
        Token token = peek();
        if (token.kind() != TokenKind.OP || !token.text().equals(symbol)) {
            throw error("Expected '" + symbol + "'" + (token.kind() == TokenKind.EOF ? "" : " but found '" + token.text() + "'"));
        }
        next();
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token next() {
        return tokens.get(index++);
    }

    private ParseException error(String message) {
        return new ParseException(message, peek().position());
    }

    enum TokenKind { NUMBER, DURATION, IDENT, OP, COMPARE, EOF }

    record Token(TokenKind kind, String text, int position) {}

    /** Splits the source into tokens; throws on characters outside the grammar. */
    static final class Tokenizer {

        private Tokenizer() {
        }

        static List<Token> tokenize(String s) {
            List<Token> out = new ArrayList<>();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                int start = i;
                if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                    boolean dot = false;
                    while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                        if (s.charAt(i) == '.') {
                            if (dot) {
                                throw new ParseException("Malformed number", start);
                            }
                            dot = true;
                        }
                        i++;
                    }
                    String num = s.substring(start, i);
                    if (i < s.length() && "mhd".indexOf(s.charAt(i)) >= 0
                            && (i + 1 == s.length() || !isIdentChar(s.charAt(i + 1)))) {
                        if (dot) {
                            throw new ParseException("Durations must be whole numbers", start);
                        }
                        i++;
                        out.add(new Token(TokenKind.DURATION, s.substring(start, i), start));
                    } else if (i < s.length() && isIdentChar(s.charAt(i))) {
                        throw new ParseException("Malformed number '" + s.substring(start, i + 1) + "'", start);
                    } else {
                        out.add(new Token(TokenKind.NUMBER, num, start));
                    }
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    while (i < s.length() && isIdentChar(s.charAt(i))) {
                        i++;
                    }
                    String ident = s.substring(start, i);
                    if (!ident.equals(ident.toLowerCase())) {
                        throw new ParseException("Identifiers are lower case: '" + ident + "'", start);
                    }
                    out.add(new Token(TokenKind.IDENT, ident, start));
                    continue;
                }
                if (c == '>' || c == '<' || c == '=' || c == '!') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '=') {
                        String op = s.substring(i, i + 2);
                        if (op.equals("!=")) {
                            throw new ParseException("'!=' is not supported", start);
                        }
                        i += 2;
                        out.add(new Token(TokenKind.COMPARE, op, start));
                    } else {
                        if (c == '=' || c == '!') {
                            throw new ParseException("Use '==' for equality", start);
                        }
                        i++;
                        out.add(new Token(TokenKind.COMPARE, String.valueOf(c), start));
                    }
                    continue;
                }
                if ("+-*/()[],".indexOf(c) >= 0) {
                    i++;
                    out.add(new Token(TokenKind.OP, String.valueOf(c), start));
                    continue;
                }
                throw new ParseException("Unexpected character '" + c + "'", start);
            }
            out.add(new Token(TokenKind.EOF, "", s.length()));
            return out;
        }

        private static boolean isIdentChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }
}
