package money.hejje.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Thresholds of the trade-cause rules ({@code hejje.analytics.cause.*}, {@code config/analytics.yaml}, plan M9.6).
 *
 * @param extendedAtr     BAD_ENTRY: the move in the trade's direction over the previous 15 minutes, in ATR(14) of M1
 * @param vwapAtr         BAD_ENTRY: entry this many ATR from VWAP in the trade's direction
 * @param cleanTargetMaeR CLEAN_TARGET: MAE better than this (R)
 * @param noiseRecoveryR  NOISE_STOP: after the stop, the price regained the entry and reached this (R)
 * @param driftR          DRIFT: time or force exit with |R| under this
 * @param earlyMaeR       EARLY: MAE reached this (R) before MFE reached {@code earlyMfeR}
 * @param earlyMfeR       EARLY: see above
 * @param lateMfeR        LATE: MFE under this (R) ...
 * @param lateRangeShare  ... and the entry in the top (long) / bottom (short) share of the previous 30 minutes' range
 * @param preEntryMinutes the window of the BAD_ENTRY move
 * @param rangeMinutes    the window of the LATE range
 * @param postExitMinutes the window after the exit NOISE_STOP looks at; the review is completed after it
 */
@ConfigurationProperties("hejje.analytics.cause")
public record TradeCauseProperties(@DefaultValue("1.5") double extendedAtr, @DefaultValue("2.0") double vwapAtr, @DefaultValue("-0.5") double cleanTargetMaeR,
        @DefaultValue("1.0") double noiseRecoveryR, @DefaultValue("0.3") double driftR, @DefaultValue("-0.7") double earlyMaeR,
        @DefaultValue("0.5") double earlyMfeR, @DefaultValue("0.3") double lateMfeR, @DefaultValue("0.2") double lateRangeShare,
        @DefaultValue("15") int preEntryMinutes, @DefaultValue("30") int rangeMinutes, @DefaultValue("30") int postExitMinutes) {}
