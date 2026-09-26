package money.hejje.ratings.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import money.hejje.common.time.HejjeClock;
import money.hejje.ratings.RatingsProperties;
import money.hejje.ratings.Surveillance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * NSE's ASM/GSM lists (docs/ratings.md, "Surveillance"): fetched with the evening D1 refresh and on demand, stored as
 * the snapshot of the day's session, and read back per session. A failed fetch stores nothing, so the previous snapshot
 * stays and reads mark it stale. Display only.
 */
@Component
public class NseSurveillance {

    private static final Logger log = LoggerFactory.getLogger(NseSurveillance.class);
    /** NSE's edge drops requests without a browser-like User-Agent (curl's default times out); no cookie is needed. */
    static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final RatingsProperties props;
    private final SurveillanceStore store;
    private final HejjeClock clock;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();

    NseSurveillance(RatingsProperties props, SurveillanceStore store, HejjeClock clock, ObjectMapper json) {
        this.props = props;
        this.store = store;
        this.clock = clock;
        this.json = json;
    }

    /** The outcome of one fetch: the session it was stored for and the number of flagged symbols, or the error. */
    public record Result(String date, boolean ok, int flags, String error) {
    }

    /** Fetches both reports and stores them as today's snapshot. Never throws: a failure is logged and returned. */
    public Result refresh() {
        LocalDate date = clock.today();
        if (!props.surveillance().enabled()) {
            return new Result(date.toString(), false, 0, "disabled (hejje.ratings.surveillance.enabled=false)");
        }
        try {
            SurveillanceParser.Report asm = SurveillanceParser.asm(get(props.surveillance().asmUrl()));
            SurveillanceParser.Report gsm = SurveillanceParser.gsm(get(props.surveillance().gsmUrl()));
            Map<String, SurveillanceParser.Flag> flags = SurveillanceParser.merge(gsm, asm);
            if (flags.isEmpty()) {
                throw new IllegalStateException("both reports are empty");
            }
            store.replace(date, asm.date(), gsm.date(), clock.now(), flags.values());
            log.info("NSE surveillance lists for {}: {} flagged (ASM {} of {}, GSM {} of {})", date, flags.size(), asm.flags().size(), asm.date(),
                    gsm.flags().size(), gsm.date());
            return new Result(date.toString(), true, flags.size(), null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("NSE surveillance lists for {} not fetched, the previous lists stay (stale): {}", date, e.toString());
            return new Result(date.toString(), false, 0, e.toString());
        }
    }

    /** The measure per symbol as of {@code session}: null for every symbol while no snapshot on or before it exists. */
    public Function<String, Surveillance> asOf(LocalDate session) {
        Optional<LocalDate> snapshot = store.latest(session);
        if (snapshot.isEmpty()) {
            return symbol -> null;
        }
        LocalDate asOf = snapshot.get();
        boolean stale = asOf.isBefore(session);
        Map<String, SurveillanceParser.Flag> flags = store.flags(asOf);
        return symbol -> {
            SurveillanceParser.Flag f = flags.get(symbol);
            return f == null ? new Surveillance("NONE", null, asOf, stale) : new Surveillance(f.flag(), f.code(), asOf, stale);
        };
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", USER_AGENT)
                .header("Accept", "application/json").header("Accept-Language", "en-US,en;q=0.9").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(url + " returned HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }
}
