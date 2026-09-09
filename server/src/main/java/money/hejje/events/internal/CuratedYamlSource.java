package money.hejje.events.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import money.hejje.common.time.HejjeClock;
import money.hejje.events.EventProperties;
import money.hejje.events.EventScope;
import money.hejje.events.EventSource;
import money.hejje.events.EventType;
import money.hejje.events.MarketEvent;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Curated calendar files (config/events/*.yaml, docs/events.md): {@code events: [{type, title, date, time?, end_date?, confidence?, symbol?}]}.
 * A {@code symbol} makes the event INSTRUMENT-scoped when it resolves in the instrument master.
 */
@Component
public class CuratedYamlSource implements EventSource {

    private static final Logger log = LoggerFactory.getLogger(CuratedYamlSource.class);

    private final EventProperties props;
    private final ResourceLoader resources;
    private final InstrumentService instruments;
    private final HejjeClock clock;

    CuratedYamlSource(EventProperties props, ResourceLoader resources, InstrumentService instruments, HejjeClock clock) {
        this.props = props;
        this.resources = resources;
        this.instruments = instruments;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "curated";
    }

    @Override
    public boolean enabled() {
        return props.curated().enabled();
    }

    @Override
    public List<MarketEvent> events(LocalDate from, LocalDate to) {
        List<MarketEvent> out = new ArrayList<>();
        for (String file : props.curated().files()) {
            try {
                Resource resource = resources.getResource(file);
                if (!resource.exists()) {
                    log.warn("Curated event file {} not found", file);
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    out.addAll(parse(new ObjectMapper(new YAMLFactory()).readTree(in), from, to, instruments::resolve, clock.zone(), clock.now(), name()));
                }
            } catch (Exception e) {
                log.warn("Curated event file {} unreadable: {}", file, e.getMessage());
            }
        }
        return out;
    }

    /** Pure parser (also used by tests). Rows outside {@code [from, to]} or with an unknown type/date are skipped. */
    public static List<MarketEvent> parse(JsonNode root, LocalDate from, LocalDate to, java.util.function.Function<String, java.util.Optional<Instrument>> resolver,
            ZoneId zone, Instant importedAt, String source) {
        List<MarketEvent> out = new ArrayList<>();
        for (JsonNode n : root.path("events")) {
            try {
                EventType type = EventType.valueOf(n.path("type").asText().trim().toUpperCase());
                LocalDate date = LocalDate.parse(n.path("date").asText());
                if (date.isBefore(from) || date.isAfter(to)) {
                    continue;
                }
                LocalTime time = n.hasNonNull("time") ? LocalTime.parse(n.path("time").asText()) : null;
                LocalDate endDate = n.hasNonNull("end_date") ? LocalDate.parse(n.path("end_date").asText()) : null;
                double confidence = n.hasNonNull("confidence") ? n.path("confidence").asDouble() : 0.9;
                String title = n.path("title").asText(type.name());
                String symbol = n.hasNonNull("symbol") ? n.path("symbol").asText().trim().toUpperCase() : null;
                Instrument instrument = symbol == null ? null : resolver.apply(symbol).orElse(null);
                EventScope scope = instrument != null ? EventScope.INSTRUMENT : EventScope.MARKET;
                Map<String, Object> raw = new java.util.LinkedHashMap<>();
                n.fields().forEachRemaining(f -> raw.put(f.getKey(), f.getValue().isNumber() ? f.getValue().numberValue() : f.getValue().asText()));
                out.add(Events.on(type, scope, instrument == null ? null : instrument.id(), symbol, title, date, time, endDate, source, confidence, raw, zone, importedAt));
            } catch (RuntimeException e) {
                log.warn("Skipping curated event {}: {}", n, e.getMessage());
            }
        }
        return out;
    }
}
