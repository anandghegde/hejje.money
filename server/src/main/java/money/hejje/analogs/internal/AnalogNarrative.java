package money.hejje.analogs.internal;

import java.util.List;
import java.util.Locale;
import money.hejje.analogs.AnalogSummary;

/**
 * The templated read (README rule 11): five sentences built from the evidence fields with hedged wording, no LLM. Every
 * rate is quoted with its count; thin, weak, risky or outlier-driven evidence gets the careful variant.
 */
public final class AnalogNarrative {

    private AnalogNarrative() {
    }

    /** @param horizon how the forward window reads after a verb, for example "over the next 5 sessions" or "over the rest of the session (to 15:10)" */
    public static List<String> read(AnalogSummary.Outcome o, String qualityTag, double medianQuality, String what, String horizon) {
        if (o.count() == 0) {
            return List.of("No similar past " + what + " were found, so there is no historical evidence either way.",
                    "Risk cannot be described without matches.", "Reliability: none.", "Match quality: none.",
                    "Takeaway: no read; this is an absence of evidence, not a neutral signal.");
        }
        String distribution = String.format(Locale.ROOT, "In %d similar past %s, price ended higher %s %d times (%.0f %%); the median move was %+.2f %% and half of "
                + "the outcomes fell between %+.2f %% and %+.2f %%.", o.count(), what, horizon, Math.round(o.winRate() * o.count()), o.winRate() * 100,
                o.median(), o.p25(), o.p75());
        String risk = String.format(Locale.ROOT, "Along the way the typical worst point was %+.2f %% (a quarter of the matches saw %+.2f %% or worse) and "
                + "the typical best point %+.2f %%%s", o.maeMedian(), o.maeP25(), o.mfeMedian(),
                "HIGH".equals(o.risk()) ? "; be careful: adverse moves were large for this horizon." : ".");
        String reliability = switch (o.reliability()) {
            case "HIGH" -> String.format(Locale.ROOT, "Reliability is high: %d matches from %d symbols across %d years.", o.count(), o.distinctSymbols(),
                    o.distinctYears());
            case "MEDIUM" -> String.format(Locale.ROOT, "Reliability is medium: %d matches from %d symbols across %d years.", o.count(),
                    o.distinctSymbols(), o.distinctYears());
            case "LOW" -> String.format(Locale.ROOT, "Reliability is low: only %d matches from %d symbols across %d years; treat the numbers as anecdotal.",
                    o.count(), o.distinctSymbols(), o.distinctYears());
            default -> String.format(Locale.ROOT, "Be careful: %d matches are too few to read a direction from.", o.count());
        };
        String quality = String.format(Locale.ROOT, "Match quality is %s (median %.1f of 5)%s", qualityTag.toLowerCase(Locale.ROOT), medianQuality,
                "WEAK".equals(qualityTag) ? "; be careful: the matches only loosely resemble the current setup." : ".");
        String lean = switch (o.direction()) {
            case "BULLISH_STRONG" -> "similar setups have historically leaned clearly higher";
            case "BULLISH" -> "similar setups have historically leaned higher";
            case "BEARISH_STRONG" -> "similar setups have historically leaned clearly lower";
            case "BEARISH" -> "similar setups have historically leaned lower";
            case "MIXED" -> "similar setups have shown no consistent lean";
            default -> "there is not enough evidence for a lean";
        };
        String takeaway = "Takeaway: " + lean + " " + horizon + (o.outlier() ? "; be careful: a few extreme matches move the average, so prefer the median"
                : "") + ("WIDE".equals(o.consistency()) ? "; outcomes were widely spread" : "") + ". This is historical context, not a forecast.";
        return List.of(distribution, risk, reliability, quality, takeaway);
    }
}
