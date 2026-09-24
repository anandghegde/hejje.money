package money.hejje.analogs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import money.hejje.analogs.AnalogSummary;
import org.junit.jupiter.api.Test;

class AnalogNarrativeTest {

    static AnalogSummary.Outcome outcome(int count, double winRate, double median, String direction, String reliability, String risk, String consistency,
            boolean outlier) {
        return new AnalogSummary.Outcome("5", count, winRate, median, median, -0.8, 2.1, 6, -4, -1.1, -2.3, 1.9, 3.2, List.of(), List.of(), List.of(), 22, 6,
                direction, consistency, reliability, risk, outlier);
    }

    @Test
    void fiveSentencesQuoteEveryRateWithItsCount() {
        List<String> read = AnalogNarrative.read(outcome(40, 0.675, 1.24, "BULLISH_STRONG", "HIGH", "MODERATE", "NORMAL", false), "STRONG", 4.2,
                "15-session windows", "over the next 5 sessions");
        assertThat(read).hasSize(5);
        assertThat(read.get(0)).isEqualTo("In 40 similar past 15-session windows, price ended higher over the next 5 sessions 27 times (68 %); the median move was "
                + "+1.24 % and half of the outcomes fell between -0.80 % and +2.10 %.");
        assertThat(read.get(1)).startsWith("Along the way the typical worst point was -1.10 % (a quarter of the matches saw -2.30 % or worse)");
        assertThat(read.get(2)).isEqualTo("Reliability is high: 40 matches from 22 symbols across 6 years.");
        assertThat(read.get(3)).isEqualTo("Match quality is strong (median 4.2 of 5).");
        assertThat(read.get(4)).isEqualTo("Takeaway: similar setups have historically leaned clearly higher over the next 5 sessions. "
                + "This is historical context, not a forecast.");
    }

    @Test
    void thinWeakRiskyAndOutlierEvidenceGetsTheCarefulVariants() {
        List<String> read = AnalogNarrative.read(outcome(6, 0.5, 0.3, "INSUFFICIENT", "INSUFFICIENT", "HIGH", "WIDE", true), "WEAK", 2.4,
                "sessions", "over the rest of the session");
        assertThat(read.get(1)).endsWith("be careful: adverse moves were large for this horizon.");
        assertThat(read.get(2)).isEqualTo("Be careful: 6 matches are too few to read a direction from.");
        assertThat(read.get(3)).endsWith("be careful: the matches only loosely resemble the current setup.");
        assertThat(read.get(4)).contains("there is not enough evidence for a lean").contains("a few extreme matches move the average")
                .contains("outcomes were widely spread");
    }

    @Test
    void noMatchesIsAnAbsenceOfEvidence() {
        List<String> read = AnalogNarrative.read(outcome(0, 0, 0, "INSUFFICIENT", "INSUFFICIENT", "NONE", "NONE", false), "NONE", 0, "sessions",
                "over the rest of the session");
        assertThat(read).hasSize(5);
        assertThat(read.get(4)).contains("absence of evidence");
    }
}
