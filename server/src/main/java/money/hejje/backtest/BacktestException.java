package money.hejje.backtest;

/** A backtest could not be set up or run (bad spec, no data, unsupported definition). */
public class BacktestException extends RuntimeException {
    public BacktestException(String message) {
        super(message);
    }
}
