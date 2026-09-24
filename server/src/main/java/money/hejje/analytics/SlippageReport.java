package money.hejje.analytics;

import java.time.LocalDate;

/**
 * Entry and exit slippage for a period (plan M4.5), with passive entries apart (plan M9.8): {@code passive} is null when
 * the period has none.
 */
public record SlippageReport(String mode, LocalDate from, LocalDate to, PerformanceMath.SlippageStats slippage, PassiveEntries passive) {

    public SlippageReport(String mode, LocalDate from, LocalDate to, PerformanceMath.SlippageStats slippage) {
        this(mode, from, to, slippage, null);
    }

    /**
     * Passive ({@code limit_touch}) entry orders placed in the period: how many filled (fully or in part), how many were
     * cancelled unfilled ({@code ENTRY_NOT_FILLED}), the fill rate, the mean seconds from placement to the first fill,
     * and the entry slippage against the signal's price for passive and for market entries (bps, positive = worse).
     */
    public record PassiveEntries(int placed, int filled, int notFilled, Double fillRate, Double meanSecondsToFill, Double passiveEntrySlippageBps,
            int passiveWithSlippage, Double marketEntrySlippageBps, int marketWithSlippage) {}
}
