package money.hejje.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.time.HejjeClock;
import money.hejje.events.EventRisk;
import money.hejje.events.EventService;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsService;
import money.hejje.pulse.PulseService;
import money.hejje.pulse.SectorStrength;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoringService;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Builds the Context Card from the live adjusters and context services (docs/decisions.md). */
@Service
public class ContextService {

    private static final Logger log = LoggerFactory.getLogger(ContextService.class);

    private final ScoringService scoring;
    private final StrategyService strategies;
    private final InstrumentService instruments;
    private final RegimeService regime;
    private final PulseService pulse;
    private final EventService events;
    private final NewsService news;
    private final HejjeClock clock;

    ContextService(ScoringService scoring, StrategyService strategies, InstrumentService instruments, RegimeService regime, PulseService pulse, EventService events,
            NewsService news, HejjeClock clock) {
        this.scoring = scoring;
        this.strategies = strategies;
        this.instruments = instruments;
        this.regime = regime;
        this.pulse = pulse;
        this.events = events;
        this.news = news;
        this.clock = clock;
    }

    public StrategyContext strategyContext(UUID versionId, UUID instrumentId) {
        StrategyVersion version = strategies.versionById(versionId).orElseThrow(() -> new StrategyException.NotFound("Strategy version " + versionId + " not found"));
        List<Adjustment> adjustments;
        try {
            adjustments = scoring.adjustments(version, instrumentId);
        } catch (RuntimeException e) {
            log.warn("Context adjusters failed for {}: {}", versionId, e.getMessage());
            adjustments = List.of();
        }
        ContextItem technical = fromAdjuster(adjustments, "Technical compatibility", "Technical fit", 8, -10, "Strong", "Forming", "Weak");
        ContextItem marketRegime = fromAdjuster(adjustments, "Current regime", "Market regime", 10, -10, "Favorable", "Neutral", "Unfavorable");
        ContextItem eventRisk = eventItem(instrumentId, adjustments);
        ContextItem newsBias = newsItem(instrumentId, adjustments);
        ContextItem sector = sectorItem(instrumentId);
        String nextEvent = null;
        try {
            EventRisk risk = events.risk(instrumentId);
            nextEvent = events.nextEventLine(risk);
        } catch (RuntimeException e) {
            // the row already says unknown
        }
        int net = 0;
        for (ContextItem item : List.of(marketRegime, eventRisk, newsBias)) {
            net += item.delta() == null ? 0 : item.delta();
        }
        return new StrategyContext(versionId, instrumentId, clock.now(), technical, marketRegime, newsBias, eventRisk, sector, nextEvent, net,
                List.of(technical, marketRegime, newsBias, eventRisk, sector));
    }

    /** Maps an adjuster's delta onto GREEN (top third of its range) / AMBER / RED (bottom third); "unknown" evidence → UNKNOWN. */
    static ContextItem fromAdjuster(List<Adjustment> adjustments, String adjusterName, String itemName, int max, int min, String good, String mid, String bad) {
        Optional<Adjustment> a = adjustments.stream().filter(x -> x.name().equals(adjusterName)).findFirst();
        if (a.isEmpty()) {
            return ContextItem.unknown(itemName, adjusterName + " not computed");
        }
        Adjustment adj = a.get();
        if (adj.delta() == 0 && !adj.evidence().isEmpty() && looksUnavailable(adj.evidence().get(0))) {
            return ContextItem.unknown(itemName, adj.evidence().get(0));
        }
        double span = max - min;
        double position = span == 0 ? 0.5 : (adj.delta() - min) / span;
        ContextItem.Status status = position >= 2.0 / 3 ? ContextItem.Status.GREEN : position <= 1.0 / 3 ? ContextItem.Status.RED : ContextItem.Status.AMBER;
        String value = status == ContextItem.Status.GREEN ? good : status == ContextItem.Status.RED ? bad : mid;
        return new ContextItem(itemName, status, value, adj.delta(), adj.evidence());
    }

    static boolean looksUnavailable(String evidence) {
        String e = evidence.toLowerCase(Locale.ROOT);
        return e.contains("unavailable") || e.contains("unknown") || e.contains("disabled") || e.contains("no instrument") || e.contains("no recent candles")
                || e.contains("stale");
    }

    private ContextItem eventItem(UUID instrumentId, List<Adjustment> adjustments) {
        EventRisk risk;
        try {
            risk = events.risk(instrumentId);
        } catch (RuntimeException e) {
            return ContextItem.unknown("Event risk", "event service unavailable: " + e.getMessage());
        }
        if (!risk.available()) {
            return ContextItem.unknown("Event risk", risk.evidence().isEmpty() ? "event service unavailable" : risk.evidence().get(0));
        }
        Integer delta = adjustments.stream().filter(x -> x.name().equals("Event risk")).map(Adjustment::delta).findFirst().orElse(null);
        ContextItem.Status status = switch (risk.level()) {
            case LOW -> ContextItem.Status.GREEN;
            case MEDIUM -> ContextItem.Status.AMBER;
            case HIGH -> ContextItem.Status.RED;
        };
        return new ContextItem("Event risk", status, capitalise(risk.level().name()), delta, risk.evidence());
    }

    private ContextItem newsItem(UUID instrumentId, List<Adjustment> adjustments) {
        NewsBias bias;
        try {
            bias = news.bias(instrumentId);
        } catch (RuntimeException e) {
            return ContextItem.unknown("News bias", "news unavailable: " + e.getMessage());
        }
        if (!bias.available()) {
            return ContextItem.unknown("News bias", bias.evidence().isEmpty() ? "news unavailable" : bias.evidence().get(0));
        }
        Integer delta = adjustments.stream().filter(x -> x.name().equals("News context")).map(Adjustment::delta).findFirst().orElse(null);
        ContextItem.Status status = bias.score() >= 0.2 ? ContextItem.Status.GREEN : bias.score() <= -0.2 ? ContextItem.Status.RED : ContextItem.Status.AMBER;
        String value = capitalise(bias.label().name().replace('_', ' ')) + String.format(Locale.ROOT, " %+.1f", bias.score());
        return new ContextItem("News bias", status, value, delta, bias.evidence());
    }

    private ContextItem sectorItem(UUID instrumentId) {
        Instrument instrument = instrumentId == null ? null : instruments.findById(instrumentId).orElse(null);
        if (instrument == null) {
            return ContextItem.unknown("Sector", "no instrument");
        }
        String symbol = instrument.isDerivative() && instrument.underlying() != null ? "NSE:" + instrument.underlying() : instrument.hejjeSymbol().format();
        Optional<String> sectorName = news.sectorOf(symbol);
        if (sectorName.isEmpty()) {
            return ContextItem.unknown("Sector", "no sector alias for " + symbol + " (config/aliases.yaml)");
        }
        try {
            for (SectorStrength s : pulse.current().market().sectors()) {
                if (s.name().equalsIgnoreCase(sectorName.get())) {
                    ContextItem.Status status = switch (s.label()) {
                        case STRONG -> ContextItem.Status.GREEN;
                        case NEUTRAL -> ContextItem.Status.AMBER;
                        case WEAK -> ContextItem.Status.RED;
                        case UNKNOWN -> ContextItem.Status.UNKNOWN;
                    };
                    String value = status == ContextItem.Status.UNKNOWN ? "Unknown" : capitalise(s.label().name());
                    List<String> evidence = new ArrayList<>();
                    evidence.add(s.name() + " (" + s.symbol() + "): " + (s.changePct() == null ? "no data" : String.format(Locale.ROOT, "%+.2f%% on the day, %+.2f points vs the index",
                            s.changePct(), s.relativePct())));
                    return new ContextItem("Sector", status, value, null, evidence);
                }
            }
        } catch (RuntimeException e) {
            return ContextItem.unknown("Sector", "pulse unavailable: " + e.getMessage());
        }
        return ContextItem.unknown("Sector", sectorName.get() + " has no sector index in config/universe/sectors.yaml");
    }

    static String capitalise(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** The current regime for callers that render the header. */
    public Optional<RegimeSnapshot> currentRegime() {
        try {
            return Optional.of(regime.current());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
