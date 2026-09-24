package money.hejje.jev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import money.hejje.calibration.CalibrationService;
import money.hejje.calibration.LabelRule;
import money.hejje.calibration.Prediction;
import money.hejje.common.Side;
import money.hejje.instruments.InstrumentService;
import money.hejje.llm.JevQuestion;
import money.hejje.llm.JevQuestionSet;
import money.hejje.llm.JevQuestionSets;
import money.hejje.llm.JevResult;
import money.hejje.llm.JevService;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalGeneratedEvent;
import money.hejje.signals.SignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * A second opinion from Jev on every strategy signal (plan M9.5, docs/jev.md "Signal check"): one call with the stage-2
 * questions on the signal's instrument and side, off the engine thread and within the Jev deadline. The answer is added
 * to the signal's evidence ({@code jevCheck}) and recorded for calibration as {@code signal-check}. It never creates,
 * sizes or blocks a trade: with the gate at {@code caution} a disagreement is a recommendation caution, at
 * {@code approval} it also turns an AUTO execution into an approval, and a gate above {@code off} is refused until
 * {@link CalibrationService#passes} holds for the question set's version. Bot signals are not checked (a Jev bot's
 * entries already are its stage 2). The backtester never calls it.
 */
@Service
public class SignalCheck implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SignalCheck.class);
    public static final String PURPOSE = "signal-check";
    public static final String DISAGREES = "JEV_DISAGREES";

    public enum Gate { OFF, CAUTION, APPROVAL }

    /**
     * One check: {@code agrees} when the setup matches the side and P(setup) reaches the set's {@code min-setup-prob}.
     */
    public record Result(String setup, double p, double composite, String version, boolean agrees, String reason) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("setup", setup);
            m.put("p", p);
            m.put("composite", composite);
            m.put("version", version);
            m.put("agrees", agrees);
            if (reason != null) {
                m.put("reason", reason);
            }
            return m;
        }

        /** "Jev agrees: long_continuation p=0.71, composite 0.62 (signal-check v1)". */
        public String line() {
            return "Jev " + (agrees ? "agrees" : "disagrees") + ": " + setup + " p=" + p + ", composite " + composite + " (" + PURPOSE + " v" + version + ")"
                    + (agrees || reason == null ? "" : " — " + reason);
        }

        @SuppressWarnings("unchecked")
        static Optional<Result> fromEvidence(Signal s) {
            for (Map<String, Object> e : s.evidence()) {
                if (e.get("jevCheck") instanceof Map<?, ?> m) {
                    Map<String, Object> c = (Map<String, Object>) m;
                    return Optional.of(new Result(String.valueOf(c.get("setup")), ((Number) c.get("p")).doubleValue(), ((Number) c.get("composite")).doubleValue(),
                            String.valueOf(c.get("version")), Boolean.TRUE.equals(c.get("agrees")), (String) c.get("reason")));
                }
            }
            return Optional.empty();
        }
    }

    private final SignalCheckProperties props;
    private final JevService jev;
    private final JevQuestionSets sets;
    private final JevMarketState marketState;
    private final SignalService signals;
    private final InstrumentService instruments;
    private final CalibrationService calibration;
    private final ObjectMapper json;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, CompletableFuture<Optional<Result>>> checks = new ConcurrentHashMap<>();
    private volatile Gate gate;
    private volatile boolean enabled;

    SignalCheck(SignalCheckProperties props, JevService jev, JevQuestionSets sets, JevMarketState marketState, SignalService signals,
            InstrumentService instruments, CalibrationService calibration, ObjectMapper json) {
        this.props = props;
        this.jev = jev;
        this.sets = sets;
        this.marketState = marketState;
        this.signals = signals;
        this.instruments = instruments;
        this.calibration = calibration;
        this.json = json;
        this.gate = Gate.valueOf(props.gate().trim().toUpperCase(Locale.ROOT));
        this.enabled = props.enabled();
    }

    /** Startup: a gate above {@code off} needs the check enabled and calibration passing, else the application does not start. */
    @Override
    public void afterSingletonsInstantiated() {
        String refusal = refusal(gate);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
    }

    public boolean enabled() {
        return enabled && jev.enabled();
    }

    /** Switches the check at runtime (it only annotates signals; turning it off sets the gate back to off). */
    public void enabled(boolean on) {
        enabled = on;
        if (!on) {
            gate = Gate.OFF;
        }
    }

    public Gate gate() {
        return gate;
    }

    /** Changes the gate at runtime, under the same rule as at startup; returns the refusal, or null when set. */
    public String gate(Gate wanted) {
        String refusal = refusal(wanted);
        if (refusal == null) {
            gate = wanted;
        }
        return refusal;
    }

    String refusal(Gate wanted) {
        if (wanted == Gate.OFF) {
            return null;
        }
        if (!enabled) {
            return "hejje.jev.signal-check.gate=" + wanted.name().toLowerCase(Locale.ROOT) + " needs hejje.jev.signal-check.enabled=true";
        }
        String version = sets.get(props.questionSet()).version();
        if (!calibration.passes(PURPOSE, version)) {
            return "hejje.jev.signal-check.gate=" + wanted.name().toLowerCase(Locale.ROOT) + " is refused: calibration of " + PURPOSE + " v" + version
                    + " does not pass the bar in docs/calibration.md (GET /api/v1/calibration?purpose=" + PURPOSE + ")";
        }
        return null;
    }

    @EventListener
    void onSignal(SignalGeneratedEvent event) {
        if (enabled()) {
            start(event.signalId());
        }
    }

    /** The check of a signal, waiting at most {@code wait} (it starts now when it has not). Empty when off, failed or late. */
    public Optional<Result> result(UUID signalId, Duration wait) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            return start(signalId).get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The check stored on the signal, if it has one (no waiting). */
    public static Optional<Result> stored(Signal s) {
        return Result.fromEvidence(s);
    }

    private CompletableFuture<Optional<Result>> start(UUID signalId) {
        if (checks.size() > 2000) {
            checks.clear(); // results live on the signals; this map only joins concurrent askers
        }
        return checks.computeIfAbsent(signalId, id -> CompletableFuture.supplyAsync(() -> run(id), calls));
    }

    private Optional<Result> run(UUID signalId) {
        try {
            Signal s = signals.find(signalId).orElse(null);
            if (s == null || isBotSignal(s)) {
                return Optional.empty();
            }
            Optional<Result> existing = Result.fromEvidence(s);
            if (existing.isPresent()) {
                return existing;
            }
            String symbol = instruments.findById(s.instrumentId()).map(i -> i.hejjeSymbol().format()).orElse(null);
            Map<String, JevMarketState.Stock> stocks = symbol == null ? Map.of() : marketState.stocks(List.of(symbol));
            JevMarketState.Stock stock = stocks.get(symbol);
            if (stock == null) {
                return Optional.empty();
            }
            boolean longSide = s.side() == Side.BUY;
            JevQuestionSet set = sets.get(props.questionSet());
            ObjectNode state = json.createObjectNode();
            state.put("symbol", symbol);
            state.put("side", longSide ? "long" : "short");
            state.set("stock", JevBotState.stock(stock.features()));
            state.set("index", marketState.index(stocks));
            ObjectNode book = JevBotState.book(stock.features());
            JevQuestionSet asked = set;
            if (book != null) {
                state.set("book", book);
            } else {
                List<String> bookQuestions = new ArrayList<>();
                set.params().path("book-questions").forEach(q -> bookQuestions.add(q.asText()));
                asked = set.without(bookQuestions);
            }
            JevResult r = jev.evaluate(PURPOSE, signalId.toString(), state, asked);
            if (!r.ok()) {
                return Optional.empty();
            }
            Map<String, Integer> levels = new LinkedHashMap<>();
            asked.questions().forEach((k, q) -> {
                if (q instanceof JevQuestion.Score sc) {
                    levels.put(k, sc.levels().size());
                }
            });
            JevBotRules.Entry e = JevBotRules.stage2(r, longSide, levels, asked.params());
            String wanted = longSide ? "long_continuation" : "short_continuation";
            double min = asked.params().path("min-setup-prob").asDouble(0.55);
            boolean agrees = wanted.equals(e.setup()) && e.pSetup() >= min;
            String reason = agrees ? null : !wanted.equals(e.setup()) ? "setup " + e.setup() + ", not " + wanted : "P(setup) " + e.pSetup() + " under " + min;
            Result result = new Result(e.setup(), e.pSetup(), e.composite(), asked.version(), agrees, reason);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("condition", "jevCheck");
            entry.put("jevCheck", result.toMap());
            signals.annotate(signalId, entry);
            if (r.callId() != null && s.stop() != null) {
                calibration.record(new Prediction("jev", r.callId().toString(), "setup", PURPOSE, asked.version(), Math.max(0, Math.min(1, e.pSetup())),
                        s.instrumentId(), LabelRule.ENTRY_1R, s.side(), s.stop(), s.createdAt()));
            }
            return Optional.of(result);
        } catch (RuntimeException e) {
            log.warn("Jev signal check of {} failed: {}", signalId, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isBotSignal(Signal s) {
        return s.evidence().stream().anyMatch(e -> "bot".equals(e.get("source")));
    }
}
