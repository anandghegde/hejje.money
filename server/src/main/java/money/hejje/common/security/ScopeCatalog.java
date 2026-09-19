package money.hejje.common.security;

import java.util.LinkedHashSet;
import java.util.Set;

/** The fixed set of API scopes (PRD section 48.2). Authorities are {@code SCOPE_<scope>}. */
public final class ScopeCatalog {

    public static final String MARKET_READ = "market:read";
    public static final String STRATEGIES_READ = "strategies:read";
    public static final String STRATEGIES_WRITE = "strategies:write";
    public static final String ORDERS_PREPARE = "orders:prepare";
    public static final String ORDERS_EXECUTE = "orders:execute";
    public static final String ORDERS_CANCEL = "orders:cancel";
    public static final String POSITIONS_CLOSE = "positions:close";
    public static final String RISK_READ = "risk:read";
    public static final String RISK_WRITE = "risk:write";
    public static final String ADMIN = "admin";
    /** Create and control replay sessions of a SIM instance (plan M7.2). */
    public static final String SIM_RUN = "sim:run";

    public static final Set<String> ALL = Set.of(MARKET_READ, STRATEGIES_READ, STRATEGIES_WRITE, ORDERS_PREPARE,
            ORDERS_EXECUTE, ORDERS_CANCEL, POSITIONS_CLOSE, RISK_READ, RISK_WRITE, ADMIN, SIM_RUN);

    public static final String AUTHORITY_PREFIX = "SCOPE_";

    private ScopeCatalog() {
    }

    public static boolean isValid(String scope) {
        return ALL.contains(scope);
    }

    /** Validates a requested scope set; throws on unknown scopes, preserves order. */
    public static Set<String> validate(Iterable<String> scopes) {
        Set<String> result = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (!isValid(scope)) {
                throw new IllegalArgumentException("Unknown scope: " + scope);
            }
            result.add(scope);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("At least one scope is required");
        }
        return result;
    }

    public static String authority(String scope) {
        return AUTHORITY_PREFIX + scope;
    }
}
