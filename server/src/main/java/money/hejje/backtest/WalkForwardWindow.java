package money.hejje.backtest;

import java.time.LocalDate;

/** One walk-forward test window and its out-of-sample expectancy (used for the score's stability component). */
public record WalkForwardWindow(int index, LocalDate trainFrom, LocalDate trainTo, LocalDate testFrom, LocalDate testTo, int trades,
        double expectancyR) {
}
