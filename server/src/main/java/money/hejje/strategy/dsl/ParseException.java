package money.hejje.strategy.dsl;

/** A syntax error in a condition, with the character position where it was found. */
public class ParseException extends IllegalArgumentException {

    private final int position;

    public ParseException(String message, int position) {
        super(message + " at position " + position);
        this.position = position;
    }

    public int position() {
        return position;
    }
}
