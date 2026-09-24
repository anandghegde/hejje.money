package money.hejje.bots;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import money.hejje.bots.internal.BotStore;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Timeframe;
import money.hejje.llm.JevProperties;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bot registration (plan M7.3). An EXTERNAL or LLM bot gets a generated backing strategy {@code bot_<name>} (family bot,
 * direction both, the bot's universe and timeframe, force exit 15:10, no entry rules of its own) moved straight to PAPER;
 * a STRATEGY bot runs an existing strategy. Deploy the backing strategy like any strategy (mode, instruments, autonomy,
 * budgets): the bot then trades through that deployment.
 */
@Service
public class BotService {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{1,40}");

    /**
     * What {@code POST /bots} takes. {@code exitConfirmVotes} defaults to 1 (2 for a JEV bot); {@code questionSet} is a JEV
     * bot's question set prefix (default {@code bot}).
     */
    public record Registration(String name, String version, Bot.Kind kind, LocalDate knowledgeCutoff, Set<ExecutionMode> allowedModes, List<String> universe,
            String timeframe, Integer decisionEveryMinutes, UUID strategyId, Integer exitConfirmVotes, String questionSet) {

        public Registration(String name, String version, Bot.Kind kind, LocalDate knowledgeCutoff, Set<ExecutionMode> allowedModes, List<String> universe,
                String timeframe, Integer decisionEveryMinutes, UUID strategyId) {
            this(name, version, kind, knowledgeCutoff, allowedModes, universe, timeframe, decisionEveryMinutes, strategyId, null, null);
        }
    }

    private final BotStore store;
    private final StrategyService strategies;
    private final JevProperties jev;
    private final List<java.util.function.Consumer<Bot>> registered = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Registration bookkeeping is wall time. */
    private final Clock wall = Clock.systemUTC();

    BotService(BotStore store, StrategyService strategies, JevProperties jev) {
        this.store = store;
        this.strategies = strategies;
        this.jev = jev;
    }

    /** Called with every newly registered bot (the in-process Jev bot runner connects its bots this way). */
    public void onRegistered(java.util.function.Consumer<Bot> listener) {
        registered.add(listener);
    }

    @Transactional
    public Bot register(Registration r, String by) {
        if (r.name() == null || !NAME.matcher(r.name()).matches()) {
            throw new IllegalArgumentException("name must be lower case letters, digits and _ (2-41 characters, starting with a letter)");
        }
        if (store.findByName(r.name()).isPresent()) {
            throw new IllegalArgumentException("a bot named " + r.name() + " exists");
        }
        Bot.Kind kind = r.kind() == null ? Bot.Kind.EXTERNAL : r.kind();
        Set<ExecutionMode> modes = r.allowedModes() == null || r.allowedModes().isEmpty() ? Set.of(ExecutionMode.SIM, ExecutionMode.PAPER) : r.allowedModes();
        if (r.decisionEveryMinutes() != null && r.decisionEveryMinutes() < 1) {
            throw new IllegalArgumentException("decisionEveryMinutes must be at least 1");
        }
        if (r.exitConfirmVotes() != null && (r.exitConfirmVotes() < 1 || r.exitConfirmVotes() > 10)) {
            throw new IllegalArgumentException("exitConfirmVotes must be between 1 and 10");
        }
        LocalDate cutoff = r.knowledgeCutoff();
        String questionSet = null;
        if (kind == Bot.Kind.JEV) {
            cutoff = cutoff != null ? cutoff : jev.knowledgeCutoff();
            if (cutoff == null) {
                throw new IllegalArgumentException("a JEV bot needs a knowledge cutoff: give knowledgeCutoff or set hejje.jev.knowledge-cutoff to the release date "
                        + "of " + jev.model());
            }
            questionSet = r.questionSet() == null || r.questionSet().isBlank() ? "bot" : r.questionSet().trim();
        }
        int exitVotes = r.exitConfirmVotes() != null ? r.exitConfirmVotes() : kind == Bot.Kind.JEV ? 2 : 1;
        UUID strategyId;
        Timeframe timeframe;
        List<String> universe;
        if (kind == Bot.Kind.STRATEGY) {
            if (r.strategyId() == null) {
                throw new IllegalArgumentException("a STRATEGY bot names the strategy it runs (strategyId)");
            }
            Strategy s = strategies.find(r.strategyId()).orElseThrow(() -> new IllegalArgumentException("Unknown strategy " + r.strategyId()));
            StrategyDefinition def = strategies.versionById(s.latestVersionId()).orElseThrow().definition();
            strategyId = s.id();
            timeframe = def.timeframe();
            universe = def.universe().stream().map(StrategyDefinition.UniverseEntry::text).toList();
        } else {
            if (r.universe() == null || r.universe().isEmpty()) {
                throw new IllegalArgumentException("universe (Hejje symbols the bot trades) is required");
            }
            timeframe = r.timeframe() == null ? Timeframe.M5 : timeframe(r.timeframe());
            universe = List.copyOf(r.universe());
            StrategyVersion v = strategies.create(backingYaml(r.name(), r.version(), universe, timeframe), "bot " + r.name(), by);
            strategies.changeStatus(v.strategyId(), v.version(), VersionStatus.PAPER, "bot strategy: no rules to backtest", by);
            strategyId = v.strategyId();
        }
        Instant now = wall.instant();
        Bot bot = new Bot(UUID.randomUUID(), r.name(), r.version() == null || r.version().isBlank() ? "1" : r.version(), kind, cutoff, modes,
                strategyId, timeframe, r.decisionEveryMinutes(), universe, true, by, now, now, exitVotes, questionSet);
        store.insert(bot);
        registered.forEach(l -> l.accept(bot));
        return bot;
    }

    public Optional<Bot> find(UUID id) {
        return store.find(id);
    }

    /** The bot trading through a strategy (its backing strategy, or the strategy a STRATEGY bot runs). */
    public Optional<Bot> byStrategy(UUID strategyId) {
        return store.all().stream().filter(b -> b.strategyId().equals(strategyId)).findFirst();
    }

    public List<Bot> list() {
        return store.all();
    }

    public void setEnabled(UUID id, boolean enabled) {
        store.setEnabled(id, enabled, wall.instant());
    }

    /** The backing strategy definition of an EXTERNAL or LLM bot: no entry rules of its own (the runner never evaluates them). */
    static String backingYaml(String name, String version, List<String> universe, Timeframe timeframe) {
        StringBuilder u = new StringBuilder();
        for (String s : universe) {
            u.append("  - \"").append(s.replace("\"", "")).append("\"\n");
        }
        return """
                # Backing strategy of bot %s (plan M7.3): its entries are the bot's decisions
                name: bot_%s
                family: bot
                description: Trades the decisions of bot %s v%s; the entry rule below is never evaluated.
                universe:
                %stimeframe: %s
                direction: both
                entry:
                  all:
                    - session_minutes >= 0
                stop:
                  type: percent
                  value: 1
                force_exit_time: "15:10"
                max_trades_per_day: 20
                """.formatted(name, name, name, version == null ? "1" : version, u, label(timeframe));
    }

    private static Timeframe timeframe(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        for (Timeframe tf : Timeframe.values()) {
            if (tf.name().equalsIgnoreCase(t) || label(tf).equals(t)) {
                if (tf == Timeframe.D1) {
                    break;
                }
                return tf;
            }
        }
        throw new IllegalArgumentException("timeframe must be an intraday timeframe such as 1m, 5m or 15m");
    }

    private static String label(Timeframe tf) {
        long m = tf.duration().toMinutes();
        return m % 60 == 0 ? (m / 60) + "h" : m + "m";
    }
}
