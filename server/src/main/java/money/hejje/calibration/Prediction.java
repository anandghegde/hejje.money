package money.hejje.calibration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.Side;

/**
 * One probabilistic prediction to be labelled (plan M9.2). {@code probability} is the probability the prediction gives
 * to the event its {@code rule} labels 1: for {@link LabelRule#ENTRY_1R} that +1R comes first on {@code side}, for the
 * direction rules that the price moves towards {@code side}, for {@link LabelRule#EXIT} that exiting the {@code side}
 * position was right.
 *
 * @param source    {@code jev} (source id = the call id, key = the question key) or {@code bot} (the decision id, key {@code confidence})
 * @param purpose   what is being calibrated, e.g. {@code signal-check}, {@code news.direction} or {@code bot:<name>}
 * @param version   the question set version or the bot version; answers are never pooled across versions
 * @param stop      entry rule only: the stop that defines R
 */
public record Prediction(String source, String sourceId, String key, String purpose, String version, double probability, UUID instrumentId,
        LabelRule rule, Side side, BigDecimal stop, Instant decidedAt) {

    public Prediction {
        if (source == null || sourceId == null || key == null || purpose == null || version == null || instrumentId == null || rule == null
                || side == null || decidedAt == null) {
            throw new IllegalArgumentException("A prediction needs source, id, key, purpose, version, instrument, rule, side and time");
        }
        if (!(probability >= 0 && probability <= 1)) {
            throw new IllegalArgumentException("probability must be in [0, 1]");
        }
        if (rule == LabelRule.ENTRY_1R && stop == null) {
            throw new IllegalArgumentException("an entry prediction needs its stop");
        }
    }
}
