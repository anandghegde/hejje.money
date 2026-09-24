package money.hejje.events;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.time.HejjeClock;
import money.hejje.events.internal.CsvEvents;
import money.hejje.events.internal.EventStore;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.strategy.StrategyDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Public API of the events module: calendar reads, manual/CSV writes, source refresh, event risk and strategy event rules. */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventStore store;
    private final List<EventSource> sources;
    private final InstrumentService instruments;
    private final AuditService audit;
    private final EventProperties props;
    private final HejjeClock clock;

    EventService(EventStore store, List<EventSource> sources, InstrumentService instruments, AuditService audit, EventProperties props, HejjeClock clock) {
        this.store = store;
        this.sources = sources;
        this.instruments = instruments;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Events in {@code [from, to]}: market events plus those of {@code instrumentId} (all instruments when null and {@code all}). */
    public List<MarketEvent> events(LocalDate from, LocalDate to, UUID instrumentId, boolean all) {
        if (!props.enabled()) {
            return List.of();
        }
        ZoneId zone = clock.zone();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        if (all && instrumentId == null) {
            return store.findAll(start, end);
        }
        Instrument instrument = instrumentId == null ? null : instruments.findById(instrumentId).orElse(null);
        return store.find(start, end, instrumentId, underlyingSymbol(instrument));
    }

    /** Proximity risk for an instrument (market-only when null) as of now. Never throws: LOW with an "unavailable" line instead. */
    public EventRisk risk(UUID instrumentId) {
        return riskAt(instrumentId, clock.now());
    }

    /** Event risk as it stood at {@code now} (trade reviews record it as of the entry, plan M4.5). Never throws. */
    public EventRisk riskAt(UUID instrumentId, Instant now) {
        if (!props.enabled()) {
            return EventRisk.unavailable("event service disabled (hejje.events.enabled=false)");
        }
        try {
            LocalDate today = now.atZone(clock.zone()).toLocalDate();
            Instrument instrument = instrumentId == null ? null : instruments.findById(instrumentId).orElse(null);
            Instant start = today.atStartOfDay(clock.zone()).toInstant();
            Instant end = today.plusDays(props.horizonDays()).atStartOfDay(clock.zone()).toInstant();
            List<MarketEvent> candidates = store.find(start, end, instrumentId, underlyingSymbol(instrument));
            return EventRiskEvaluator.evaluate(candidates, instrument, now, clock.zone(), props.risk());
        } catch (RuntimeException e) {
            log.warn("Event risk unavailable: {}", e.getMessage());
            return EventRisk.unavailable("event service unavailable: " + e.getMessage());
        }
    }

    /** The definition's event rules against the instrument's current risk. */
    public EventRuleOutcome rule(StrategyDefinition definition, UUID instrumentId) {
        return EventRules.apply(definition.eventRules(), risk(instrumentId));
    }

    /** "Q2 Results — Today 16:00" for the Today card, or null. */
    public String nextEventLine(EventRisk risk) {
        return risk.nextEventLine(clock.zone(), clock.today());
    }

    /** Manual add (audited). */
    public MarketEvent add(EventType type, String symbol, String title, LocalDate date, LocalTime time, LocalDate endDate, Double confidence, String by) {
        Instrument instrument = symbol == null || symbol.isBlank() ? null : instruments.resolve(symbol.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Unknown symbol " + symbol));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("by", by);
        MarketEvent event = money.hejje.events.internal.Events.on(type, instrument != null ? EventScope.INSTRUMENT : EventScope.MARKET,
                instrument == null ? null : instrument.id(), symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase(), title, date, time, endDate,
                "manual", confidence == null ? 1.0 : confidence, raw, clock.zone(), clock.now());
        store.upsert(event);
        audit.record(AuditEvent.of(AuditEventType.EVENT_ADDED, ActorType.USER).withActorId(by)
                .withPayload(Map.of("type", type.name(), "title", title, "date", date.toString(), "symbol", event.symbol() == null ? "" : event.symbol())));
        return event;
    }

    /**
     * An all-day market-wide event detected by another module (plan M9.3: {@code news-jev}), upserted by source and
     * {@code (type, date, title)} so repeated detections on a day do not duplicate it. Audited when first inserted.
     *
     * @return true when the event is new
     */
    public boolean addDetected(EventType type, LocalDate date, String title, String source, double confidence, Map<String, Object> raw) {
        if (!type.isMarketScope()) {
            throw new IllegalArgumentException(type + " is not a market event type");
        }
        MarketEvent event = money.hejje.events.internal.Events.on(type, EventScope.MARKET, null, null, title, date, null, null, source, confidence, raw,
                clock.zone(), clock.now());
        boolean inserted = store.upsert(event);
        if (inserted) {
            audit.record(AuditEvent.of(AuditEventType.EVENT_ADDED, ActorType.SYSTEM).withActorId(source)
                    .withPayload(Map.of("type", type.name(), "title", title, "date", date.toString(), "source", source)));
        }
        return inserted;
    }

    public record ImportResult(int imported, int updated, List<String> errors) {}

    /** CSV import (docs/events.md): rows with errors are reported and skipped, the rest are upserted under source {@code csv}. */
    public ImportResult importCsv(String csv, String by) {
        CsvEvents.Parsed parsed = CsvEvents.parse(csv, instruments::resolve, clock.zone(), clock.now(), "csv");
        int inserted = 0;
        for (MarketEvent e : parsed.events()) {
            if (store.upsert(e)) {
                inserted++;
            }
        }
        audit.record(AuditEvent.of(AuditEventType.EVENTS_IMPORTED, ActorType.USER).withActorId(by)
                .withPayload(Map.of("rows", parsed.events().size(), "inserted", inserted, "errors", parsed.errors().size())));
        return new ImportResult(inserted, parsed.events().size() - inserted, parsed.errors());
    }

    public record RefreshResult(LocalDate from, LocalDate to, Map<String, Integer> bySource, int inserted) {}

    /** Pulls every enabled source for {@code [from, to]} and upserts; a failing source contributes nothing. */
    public RefreshResult refresh(LocalDate from, LocalDate to) {
        Map<String, Integer> bySource = new LinkedHashMap<>();
        int inserted = 0;
        if (props.enabled()) {
            for (EventSource source : sources) {
                if (!source.enabled()) {
                    continue;
                }
                List<MarketEvent> events;
                try {
                    events = source.events(from, to);
                } catch (RuntimeException e) {
                    log.warn("Event source {} failed: {}", source.name(), e.getMessage());
                    continue;
                }
                for (MarketEvent e : events) {
                    if (store.upsert(e)) {
                        inserted++;
                    }
                }
                bySource.put(source.name(), events.size());
            }
            audit.record(AuditEvent.of(AuditEventType.EVENTS_REFRESHED, ActorType.SYSTEM)
                    .withPayload(Map.of("from", from.toString(), "to", to.toString(), "inserted", inserted, "bySource", bySource)));
        }
        return new RefreshResult(from, to, bySource, inserted);
    }

    /** The default refresh window: a week back to the horizon ahead. */
    public RefreshResult refresh() {
        LocalDate today = clock.today();
        return refresh(today.minusDays(7), today.plusDays(props.horizonDays()));
    }

    public Optional<MarketEvent> nextEvent(UUID instrumentId) {
        return Optional.ofNullable(risk(instrumentId).nextEvent());
    }

    /** Market-scope events of a session (for the regime environment). */
    public List<MarketEvent> marketEvents(LocalDate date) {
        return events(date, date, null, true);
    }

    private static String underlyingSymbol(Instrument instrument) {
        return instrument == null || !instrument.isDerivative() || instrument.underlying() == null ? null : "NSE:" + instrument.underlying();
    }

    List<EventSource> sources() {
        return new ArrayList<>(sources);
    }
}
