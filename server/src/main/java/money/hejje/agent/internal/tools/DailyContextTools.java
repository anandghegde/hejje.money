package money.hejje.agent.internal.tools;

import static money.hejje.agent.internal.tools.ToolSupport.schema;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import money.hejje.agent.AgentTool;
import money.hejje.agent.AgentToolProvider;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolException;
import money.hejje.analogs.AnalogSummary;
import money.hejje.analogs.AnalogsService;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.instruments.Instrument;
import money.hejje.ratings.Base;
import money.hejje.ratings.BaseStatus;
import money.hejje.ratings.DailyRating;
import money.hejje.ratings.RatingsService;
import money.hejje.ratings.ScreenRequest;
import money.hejje.ratings.ScreenerService;
import org.springframework.stereotype.Component;

/**
 * Read-only tools over the daily context layer (plan M8.7): ratings, bases, the screener and historical analogs. Every
 * response carries counts and tags next to its rates, so a model quotes the evidence instead of inventing statistics;
 * {@code validated=false} says the numbers have not passed the pre-registered validation and must be presented as
 * context, not as a signal.
 */
@Component
public class DailyContextTools implements AgentToolProvider {

    public record SymbolInput(String instrument, String date) {}

    public record Ratings(boolean available, String note, DailyRating rating, boolean validated) {}

    public record BasesInput(String instrument, String status, String date) {}

    public record Bases(boolean available, String note, List<Base> bases, boolean validated) {}

    public record ScreenInput(List<ScreenRequest.Filter> filters, String sort, Integer limit, String date) {}

    public record Screen(boolean available, String note, String date, int universe, int matched, List<String> fields, List<Map<String, Object>> rows) {}

    public record AnalogsInput(String instrument, String kind, Integer lookback, String checkpoint, String date) {}

    public record Analogs(boolean available, String note, AnalogSummary summary, boolean validated) {}

    private final RatingsService ratings;
    private final ScreenerService screener;
    private final AnalogsService analogs;
    private final ToolSupport support;

    DailyContextTools(RatingsService ratings, ScreenerService screener, AnalogsService analogs, ToolSupport support) {
        this.ratings = ratings;
        this.screener = screener;
        this.analogs = analogs;
        this.support = support;
    }

    @Override
    public List<AgentTool> tools() {
        String date = """
                {"type":"string","format":"date","description":"Session date; default the latest computed session"}""";
        return List.of(
                AgentTool.of("get_stock_ratings", "Daily price/volume ratings of a NIFTY 500 stock: RS rating 1-99, accumulation/distribution grade A+..E, "
                        + "technical composite 1-99, percent off the 52-week high and low, volume versus its 50-session average, industry group rank, "
                        + "with the evidence (history used, universe size, component percentiles). Technical only: no fundamentals.",
                        ScopeCatalog.MARKET_READ, schema("""
                                {"type":"object","properties":{"instrument":%s,"date":%s},"required":["instrument"],"additionalProperties":false}"""
                                .formatted(MarketContextTools.INSTRUMENT_PROP, date)),
                        SymbolInput.class, Ratings.class, this::ratings),
                AgentTool.of("get_bases", "Chart bases (flat base, cup with handle, cup, double bottom, moving-average reversal) of a stock, or of the whole "
                        + "universe by status, each with its informational trade plan (pivot, buy zone, stop, goal), lifecycle status and, once closed, the "
                        + "realised outcome in percent and in R. Hejje does not trade these setups.", ScopeCatalog.MARKET_READ, schema("""
                                {"type":"object","properties":{"instrument":%s,"status":{"type":"string","enum":%s},"date":%s},"additionalProperties":false}"""
                                .formatted(MarketContextTools.INSTRUMENT_PROP, ToolSupport.enumJson(BaseStatus.class), date)),
                        BasesInput.class, Bases.class, this::bases),
                AgentTool.of("screen_stocks", "Runs the screener over the latest session: filters of field / operator (gte, lte, gt, lt, eq, ne, in) / value, "
                        + "all of which must hold, a sort field (prefix - for descending) and a limit (default 20, at most 100). The response lists the valid "
                        + "fields. Analog win rates (analogWinRateN) come with their counts (analogCountN); quote both.", ScopeCatalog.MARKET_READ, schema("""
                                {"type":"object","properties":{"filters":{"type":"array","maxItems":12,"items":{"type":"object","properties":{
                                 "field":{"type":"string"},"op":{"type":"string","enum":["gte","lte","gt","lt","eq","ne","in"]},"value":{}},
                                 "required":["field","op","value"],"additionalProperties":false}},"sort":{"type":"string"},
                                 "limit":{"type":"integer","minimum":1,"maximum":100},"date":%s},"additionalProperties":false}""".formatted(date)),
                        ScreenInput.class, Screen.class, this::screen),
                AgentTool.of("get_historical_analogs", "What followed past windows that looked like now. kind DAILY: D1 windows of `lookback` sessions "
                        + "(5,10,15,20,25,30,40,50; default 15) across the NIFTY 500, outcomes over the next 3/5/10/15 sessions. kind SESSION: today's session "
                        + "so far at a checkpoint (09:45, 10:15, 11:15, 13:00; default the latest passed) against past sessions, outcome to 15:10. Each outcome "
                        + "has count, win rate, median, quartiles, MAE/MFE and the tags direction, consistency, reliability, risk, outlier, plus a templated "
                        + "five-sentence read. Always quote the count with a rate; INSUFFICIENT means there is no read.", ScopeCatalog.MARKET_READ, schema("""
                                {"type":"object","properties":{"instrument":%s,"kind":{"type":"string","enum":["DAILY","SESSION"]},
                                 "lookback":{"type":"integer","minimum":5,"maximum":50},"checkpoint":{"type":"string","pattern":"^[0-9]{2}:[0-9]{2}$"},
                                 "date":%s},"required":["instrument"],"additionalProperties":false}""".formatted(MarketContextTools.INSTRUMENT_PROP, date)),
                        AnalogsInput.class, Analogs.class, this::analogs));
    }

    Ratings ratings(SymbolInput in, ToolContext ctx) {
        if (!ratings.enabled()) {
            return new Ratings(false, "Daily ratings are disabled (hejje.ratings.enabled=false)", null, false);
        }
        Instrument i = support.instrument(in.instrument());
        return ratings.rating(ToolSupport.symbol(i), date(in.date()))
                .map(r -> new Ratings(true, null, r, false))
                .orElseGet(() -> new Ratings(true, ToolSupport.symbol(i) + " has no rating: it is outside the daily universe or nothing is computed yet", null, false));
    }

    Bases bases(BasesInput in, ToolContext ctx) {
        if (!ratings.enabled()) {
            return new Bases(false, "Daily ratings are disabled (hejje.ratings.enabled=false)", List.of(), false);
        }
        BaseStatus status = in.status() == null ? null : BaseStatus.valueOf(in.status());
        List<Base> found = in.instrument() == null ? ratings.bases(status, null, date(in.date()))
                : ratings.basesOf(ToolSupport.symbol(support.instrument(in.instrument())), date(in.date())).stream()
                        .filter(b -> status == null || b.status() == status).toList();
        if (in.instrument() == null && status == null) {
            found = found.stream().filter(b -> !b.status().closed()).toList(); // the open setups; ask by status for the ledger
        }
        return new Bases(true, found.size() > 50 ? "Showing 50 of " + found.size() : null, found.stream().limit(50).toList(), false);
    }

    Screen screen(ScreenInput in, ToolContext ctx) {
        if (!ratings.enabled()) {
            return new Screen(false, "Daily ratings are disabled (hejje.ratings.enabled=false)", "", 0, 0, screener.fields(), List.of());
        }
        try {
            ScreenerService.Result result = screener.run(new ScreenRequest(date(in.date()), in.filters(), in.sort(),
                    Math.min(in.limit() == null ? 20 : in.limit(), 100)));
            return new Screen(true, null, result.date(), result.universe(), result.matched(), screener.fields(), result.rows());
        } catch (IllegalArgumentException e) {
            throw ToolException.invalid(e.getMessage());
        }
    }

    Analogs analogs(AnalogsInput in, ToolContext ctx) {
        if (!analogs.enabled()) {
            return new Analogs(false, "Historical analogs are disabled (hejje.analogs.enabled=false)", null, false);
        }
        String symbol = ToolSupport.symbol(support.instrument(in.instrument()));
        boolean session = "SESSION".equals(in.kind());
        if (!session && in.lookback() != null && !analogs.lookbacks().contains(in.lookback())) {
            throw ToolException.invalid("lookback must be one of " + analogs.lookbacks());
        }
        if (session && in.checkpoint() != null && !analogs.checkpoints().contains(in.checkpoint())) {
            throw ToolException.invalid("checkpoint must be one of " + analogs.checkpoints());
        }
        var summary = session ? analogs.session(symbol, in.checkpoint(), date(in.date()))
                : analogs.daily(symbol, in.lookback() == null ? 15 : in.lookback(), date(in.date()));
        return summary.map(s -> new Analogs(true, null, s, false))
                .orElseGet(() -> new Analogs(true, session ? "No session analogs yet: no checkpoint has passed, or the bars are missing"
                        : "No daily analogs stored for " + symbol + " at that lookback", null, false));
    }

    private static LocalDate date(String text) {
        try {
            return text == null || text.isBlank() ? null : LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            throw ToolException.invalid("date must be yyyy-MM-dd");
        }
    }
}
