package money.hejje.agent;

import java.math.BigDecimal;

/**
 * What an agent asks to trade: a signal ({@code signalId}), or an instrument with side, rupee risk, stop and optional
 * entry (default the last price), target, product (default MIS) and strategy. Hejje sizes it; the agent never does.
 */
public record ProposalSpec(String signalId, String instrument, String side, BigDecimal riskRupees, BigDecimal entry, BigDecimal stop, BigDecimal target,
        String product, String strategy) {
}
