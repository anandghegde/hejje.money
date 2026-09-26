package money.hejje.swing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import money.hejje.instruments.InstrumentService;
import money.hejje.instruments.Universe;
import money.hejje.instruments.UniverseCatalog;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyFamily;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import money.hejje.swing.internal.SwingWatcher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Swing deployments and their watched setups (plan M11.4). A swing deployment is a strategy deployment of family
 * {@code swing}, one per universe, whose entries are the M8.4 trade plans (bases and reversals) that are READY (detected,
 * not triggered, not closed) as of the previous session. It has no intraday runner: the {@link SwingWatcher} watches the
 * setups on M1 bars during the session and turns a trigger into a signal on the normal signal → approval (or AUTO) →
 * execution path, carrying the plan's stop and goal, which become the position's GTT. PAPER and SIM only.
 */
@Service
public class SwingEntries {

    /** The deployment params a swing deployment understands (all optional). */
    public static final String UNIVERSE = "universe";
    public static final String TRAIL = "trail";
    public static final String MAX_HOLDING_DAYS = "max_holding_days";
    public static final String VOLUME_PACE = "volume_pace";
    public static final String MAX_CHASE_BPS = "max_chase_bps";

    private final StrategyService strategies;
    private final UniverseCatalog universes;
    private final InstrumentService instruments;
    private final HejjeProperties properties;
    private final SwingProperties swing;
    private final SwingWatcher watcher;

    SwingEntries(StrategyService strategies, UniverseCatalog universes, InstrumentService instruments, HejjeProperties properties, SwingProperties swing,
            @Lazy SwingWatcher watcher) {
        this.strategies = strategies;
        this.universes = universes;
        this.instruments = instruments;
        this.properties = properties;
        this.swing = swing;
        this.watcher = watcher;
    }

    /** A request for a swing deployment on a universe. Null fields take {@link SwingProperties}' defaults. */
    public record DeployRequest(String universe, int autonomyLevel, Boolean trail, Integer maxHoldingDays, BigDecimal volumePace, Integer maxChaseBps) {}

    /**
     * Deploys the swing strategy of {@code universe} in the server's mode (PAPER or SIM only; one enabled deployment per
     * universe). The backing strategy {@code swing_<universe>} is created on first use and moved DRAFT → PAPER (it has no
     * intraday rules to backtest; the SWING backtest judges it).
     */
    public StrategyDeployment deploy(DeployRequest r, String by) {
        ExecutionMode mode = properties.mode();
        if (!mode.simulated()) {
            throw new IllegalArgumentException("swing trading is PAPER-only until the H5 validation passes and LIVE is decided (plan Phase 11)");
        }
        String name = r.universe() == null ? "" : r.universe().trim().toLowerCase(java.util.Locale.ROOT);
        Universe universe = universes.find(name).orElseThrow(() -> new IllegalArgumentException("Unknown universe " + r.universe()));
        if (deployments().stream().anyMatch(d -> d.enabled() && name.equals(d.params().get(UNIVERSE)))) {
            throw new IllegalArgumentException("a swing deployment on " + name + " exists already (one per universe)");
        }
        List<String> symbols = new ArrayList<>();
        for (String s : universe.symbols()) {
            instruments.resolve(s).map(i -> i.hejjeSymbol().format()).ifPresent(symbols::add);
        }
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("no symbol of " + name + " resolves in the instrument master");
        }
        String slug = "swing_" + name.replace('-', '_');
        Strategy strategy = strategies.findBySlug(slug).orElse(null);
        StrategyVersion version;
        if (strategy == null) {
            version = strategies.create(backingYaml(slug, name, symbols), "swing deployment on " + name, by);
            strategies.changeStatus(version.strategyId(), version.version(), VersionStatus.PAPER, "swing strategy: judged by the SWING backtest and PAPER", by);
        } else {
            version = strategies.latestVersion(strategy.id()).orElseThrow();
            if (version.status() == VersionStatus.DRAFT) {
                strategies.changeStatus(version.strategyId(), version.version(), VersionStatus.PAPER, "swing strategy", by);
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(UNIVERSE, name);
        params.put(TRAIL, r.trail() == null || r.trail());
        params.put(MAX_HOLDING_DAYS, r.maxHoldingDays() == null ? swing.maxHoldingDays() : r.maxHoldingDays());
        params.put(VOLUME_PACE, (r.volumePace() == null ? swing.volumePace() : r.volumePace()).toPlainString());
        params.put(MAX_CHASE_BPS, r.maxChaseBps() == null ? swing.maxChaseBps() : r.maxChaseBps());
        StrategyDeployment d = strategies.deploy(version.strategyId(), version.version(), mode, symbols, r.autonomyLevel(), params, by);
        watcher.invalidate();
        return d;
    }

    /** The swing deployments of the server's mode. */
    public List<StrategyDeployment> deployments() {
        return strategies.deployments(null, properties.mode(), null).stream().filter(this::isSwing).toList();
    }

    public boolean isSwing(StrategyDeployment d) {
        return strategies.versionById(d.versionId()).map(v -> v.definition().family() == StrategyFamily.SWING).orElse(false);
    }

    /** The deployment a swing strategy trades through, if any (the newest enabled one of the mode). */
    public Optional<StrategyDeployment> deploymentOfStrategy(UUID strategyId) {
        return strategyId == null ? Optional.empty()
                : deployments().stream().filter(d -> d.strategyId().equals(strategyId)).sorted((a, b) -> Boolean.compare(b.enabled(), a.enabled())).findFirst();
    }

    /** The trigger settings of a deployment: its params over the defaults. */
    public SwingTrigger.Params params(StrategyDeployment d) {
        return new SwingTrigger.Params(decimal(d.params().get(VOLUME_PACE), swing.volumePace()), integer(d.params().get(MAX_CHASE_BPS), swing.maxChaseBps()),
                swing.minStopDistancePct(), swing.sessionMinutes());
    }

    public boolean trail(StrategyDeployment d) {
        Object t = d.params().get(TRAIL);
        return t == null || Boolean.parseBoolean(String.valueOf(t));
    }

    public int maxHoldingDays(StrategyDeployment d) {
        return integer(d.params().get(MAX_HOLDING_DAYS), swing.maxHoldingDays());
    }

    /** One watched setup today and what the watcher last saw. */
    public record Watched(UUID deploymentId, UUID baseId, UUID instrumentId, String symbol, String type, BigDecimal pivot, BigDecimal buyHigh,
            BigDecimal stop, BigDecimal goal, Long avgVolume50, String state, BigDecimal lastClose, BigDecimal pace, UUID signalId) {}

    /** The setups watched in the current session, by symbol. */
    public List<Watched> watched() {
        return watcher.watched();
    }

    static String backingYaml(String slug, String universe, List<String> symbols) {
        StringBuilder u = new StringBuilder();
        for (String s : symbols) {
            u.append("  - \"").append(s.replace("\"", "")).append("\"\n");
        }
        return """
                # Backing strategy of the swing deployment on %s (plan M11.4): its entries are the M8.4 trade plans crossing
                # their pivots, watched by the swing module; the entry rule below is never evaluated
                name: %s
                family: swing
                description: Swing entries (delivery, long only) from the READY base and reversal setups of %s; the plan's stop and goal become the GTT.
                universe:
                %stimeframe: 1m
                direction: long
                product: CNC
                entry:
                  all:
                    - session_minutes >= 0
                stop:
                  type: percent
                  value: 7
                """.formatted(universe, slug, universe, u);
    }

    private static BigDecimal decimal(Object v, BigDecimal fallback) {
        try {
            return v == null ? fallback : new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int integer(Object v, int fallback) {
        try {
            return v == null ? fallback : new BigDecimal(String.valueOf(v)).intValue();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
