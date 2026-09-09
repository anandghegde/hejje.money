package money.hejje.events.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.springframework.stereotype.Component;

/**
 * Best-effort fetch of NSE's corporate-action and board-meeting JSON feeds (off by default; the site may block
 * automated clients, in which case the CSV import path is the fallback). Any failure logs once at INFO and yields no
 * events. Field names follow the public feeds as of 2026 and are read leniently.
 */
@Component
class NseCorporateActionsFetcher implements EventSource {

    private static final Logger log = LoggerFactory.getLogger(NseCorporateActionsFetcher.class);
    private static final DateTimeFormatter NSE_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final EventProperties props;
    private final InstrumentService instruments;
    private final HejjeClock clock;
    private final ObjectMapper json;

    NseCorporateActionsFetcher(EventProperties props, InstrumentService instruments, HejjeClock clock, ObjectMapper json) {
        this.props = props;
        this.instruments = instruments;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public String name() {
        return "nse";
    }

    @Override
    public boolean enabled() {
        return props.nse().enabled();
    }

    @Override
    public List<MarketEvent> events(LocalDate from, LocalDate to) {
        List<MarketEvent> out = new ArrayList<>();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(props.nse().timeoutSeconds())).followRedirects(HttpClient.Redirect.NORMAL).build();
            out.addAll(corporateActions(fetch(client, "/api/corporates-corporateActions?index=equities"), from, to));
            out.addAll(boardMeetings(fetch(client, "/api/corporate-board-meetings?index=equities"), from, to));
        } catch (Exception e) {
            log.info("NSE event feed unavailable ({}); use POST /api/v1/events/import instead", e.toString());
        }
        return out;
    }

    private JsonNode fetch(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.nse().baseUrl() + path)).timeout(Duration.ofSeconds(props.nse().timeoutSeconds()))
                .header("Accept", "application/json").header("User-Agent", "Mozilla/5.0 (hejje)").header("Accept-Language", "en-US,en;q=0.9").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + path);
        }
        return json.readTree(response.body());
    }

    List<MarketEvent> corporateActions(JsonNode root, LocalDate from, LocalDate to) {
        List<MarketEvent> out = new ArrayList<>();
        for (JsonNode n : root.isArray() ? root : root.path("data")) {
            String symbol = n.path("symbol").asText("");
            String subject = n.path("subject").asText("");
            LocalDate exDate = date(n.path("exDate").asText(""));
            if (symbol.isEmpty() || exDate == null || exDate.isBefore(from) || exDate.isAfter(to)) {
                continue;
            }
            EventType type = classifyAction(subject);
            add(out, type, symbol, subject.isEmpty() ? type.name() : subject, exDate, n);
        }
        return out;
    }

    List<MarketEvent> boardMeetings(JsonNode root, LocalDate from, LocalDate to) {
        List<MarketEvent> out = new ArrayList<>();
        for (JsonNode n : root.isArray() ? root : root.path("data")) {
            String symbol = n.path("bm_symbol").asText(n.path("symbol").asText(""));
            String purpose = n.path("bm_purpose").asText(n.path("purpose").asText(""));
            LocalDate date = date(n.path("bm_date").asText(n.path("date").asText("")));
            if (symbol.isEmpty() || date == null || date.isBefore(from) || date.isAfter(to)) {
                continue;
            }
            String lower = purpose.toLowerCase(Locale.ROOT);
            EventType type = lower.contains("result") ? EventType.RESULTS : EventType.BOARD_MEETING;
            add(out, type, symbol, purpose.isEmpty() ? "Board meeting" : "Board meeting: " + purpose, date, n);
        }
        return out;
    }

    private void add(List<MarketEvent> out, EventType type, String symbol, String title, LocalDate date, JsonNode n) {
        String hejjeSymbol = "NSE:" + symbol.trim().toUpperCase(Locale.ROOT);
        Instrument instrument = instruments.resolve(hejjeSymbol).orElse(null);
        if (instrument == null) {
            return; // not in our universe of interest
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        n.fields().forEachRemaining(f -> raw.put(f.getKey(), f.getValue().asText()));
        out.add(Events.on(type, EventScope.INSTRUMENT, instrument.id(), hejjeSymbol, title, date, null, null, name(), 0.8, raw, clock.zone(), clock.now()));
    }

    static EventType classifyAction(String subject) {
        String s = subject.toLowerCase(Locale.ROOT);
        if (s.contains("dividend")) {
            return EventType.EX_DIVIDEND;
        }
        if (s.contains("bonus")) {
            return EventType.BONUS;
        }
        if (s.contains("split") || s.contains("sub-division") || s.contains("subdivision")) {
            return EventType.SPLIT;
        }
        if (s.contains("buyback") || s.contains("buy back")) {
            return EventType.BUYBACK;
        }
        if (s.contains("annual general meeting") || s.contains("agm") || s.contains("egm")) {
            return EventType.AGM;
        }
        return EventType.CORPORATE_ACTION;
    }

    static LocalDate date(String text) {
        try {
            return text.isBlank() ? null : LocalDate.parse(text.trim(), NSE_DATE);
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(text.trim());
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }
}
