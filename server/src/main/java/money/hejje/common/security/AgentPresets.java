package money.hejje.common.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent credential presets (PRD 48.3, plan M4.2) for {@code POST /api/v1/auth/clients {"preset": ...}}. No preset grants
 * a scope that executes, cancels or closes, changes risk, or administers ({@link #NEVER_IN_A_PRESET}): an agent key
 * prepares, a human approves.
 */
public final class AgentPresets {

    public static final String RESEARCH = "research";
    public static final String EXECUTION = "execution";
    /** A bot's client credential (plan M7.3): reads market and strategy state, sends decisions, never orders. */
    public static final String BOT = "bot";

    public static final Set<String> NEVER_IN_A_PRESET = Set.of(ScopeCatalog.ORDERS_EXECUTE, ScopeCatalog.ORDERS_CANCEL, ScopeCatalog.POSITIONS_CLOSE,
            ScopeCatalog.RISK_WRITE, ScopeCatalog.ADMIN);

    private static final Map<String, List<String>> PRESETS = Map.of(
            RESEARCH, List.of(ScopeCatalog.MARKET_READ, ScopeCatalog.STRATEGIES_READ),
            EXECUTION, List.of(ScopeCatalog.MARKET_READ, ScopeCatalog.STRATEGIES_READ, ScopeCatalog.ORDERS_PREPARE),
            BOT, List.of(ScopeCatalog.MARKET_READ, ScopeCatalog.STRATEGIES_READ, ScopeCatalog.BOT_DECIDE));

    private AgentPresets() {
    }

    public static List<String> names() {
        return List.of(RESEARCH, EXECUTION, BOT);
    }

    public static Set<String> scopes(String preset) {
        List<String> scopes = PRESETS.get(preset);
        if (scopes == null) {
            throw new IllegalArgumentException("Unknown preset '" + preset + "' (known: " + String.join(", ", names()) + ")");
        }
        return new LinkedHashSet<>(scopes);
    }
}
