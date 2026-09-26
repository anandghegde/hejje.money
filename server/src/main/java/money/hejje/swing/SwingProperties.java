package money.hejje.swing;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Swing entry settings ({@code hejje.swing.*}, plan M11.4). A swing deployment's params override the per-deployment ones.
 *
 * @param volumePace            a base triggers only when the session's projected volume is at least this multiple of its
 *                              50-session average (the M8.4 breakout volume); reversal setups have no volume condition
 * @param maxChaseBps           the entry LIMIT is the trigger price plus this many basis points, never above the buy zone
 * @param signalValidityMinutes a swing signal waits this long for its confirmation (or AUTO)
 * @param maxHoldingDays        a position that made neither goal nor stop after this many sessions is closed at the next open
 * @param reviewAfterDays       the weekly review lists positions still below their entry after this many sessions
 * @param minStopDistancePct    Kite refuses a GTT trigger closer than 0.25 % to the last price: a setup whose stop is nearer
 *                              is not entered, and a trailed stop never comes nearer
 * @param sessionMinutes        minutes of a full session (09:15-15:30), for the volume pace projection
 */
@ConfigurationProperties("hejje.swing")
public record SwingProperties(
        @DefaultValue("1.4") BigDecimal volumePace,
        @DefaultValue("20") int maxChaseBps,
        @DefaultValue("30") int signalValidityMinutes,
        @DefaultValue("30") int maxHoldingDays,
        @DefaultValue("10") int reviewAfterDays,
        @DefaultValue("0.25") BigDecimal minStopDistancePct,
        @DefaultValue("375") int sessionMinutes) {
}
