package money.hejje.backtest;

/** Where an entry signal fills: at the next bar's open (realistic default) or at the signal bar's close (parity runs). */
public enum FillModel { NEXT_OPEN, BAR_CLOSE }
