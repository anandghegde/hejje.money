package money.hejje.swing;

import java.math.BigDecimal;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;

/**
 * The swing book's limits (plan M11.3), one row per mode, editable with {@code PUT /api/v1/swing/limits}.
 *
 * @param swingCapital            the most the open swing positions may cost in total
 * @param maxOpenPositions        open swing positions at once
 * @param maxRiskPerPosition      a position's gap-adjusted risk: quantity × (stop distance + gap allowance)
 * @param gapAllowancePct         the gap allowance as a percentage of the price (a stop can be jumped by an opening gap)
 * @param maxOvernightRisk        the gap-adjusted risk of the whole swing book
 * @param maxPositionsPerIndustry open swing positions in one industry (from the swing universe's industry map)
 * @param blockBeforeEvents       no new entries on the session before an RBI policy, the Budget or an index rebalance
 * @param blockSurveillance       no entry in a stock under NSE ASM/GSM surveillance
 */
public record SwingLimits(ExecutionMode mode, Money swingCapital, int maxOpenPositions, Money maxRiskPerPosition, BigDecimal gapAllowancePct,
        Money maxOvernightRisk, int maxPositionsPerIndustry, boolean blockBeforeEvents, boolean blockSurveillance) {
}
