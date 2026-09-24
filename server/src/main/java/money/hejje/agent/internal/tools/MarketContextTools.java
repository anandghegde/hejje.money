package money.hejje.agent.internal.tools;

import static money.hejje.agent.internal.tools.ToolSupport.name;
import static money.hejje.agent.internal.tools.ToolSupport.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.agent.AgentTool;
import money.hejje.agent.AgentToolProvider;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolException;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.HejjeClock;
import money.hejje.events.EventRisk;
import money.hejje.events.EventService;
import money.hejje.events.MarketEvent;
import money.hejje.instruments.Instrument;
import money.hejje.market.MarketService;
import money.hejje.market.QuoteSnapshot;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsService;
import money.hejje.pulse.MarketPulse;
import money.hejje.pulse.PulseService;
import money.hejje.pulse.PulseSnapshot;
import money.hejje.pulse.TechnicalPulse;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.regime.Trend;
import org.springframework.stereotype.Component;

/** Market context tools (scope {@code market:read}): snapshot, regime, pulse, news bias, event calendar. */
@Component
public class MarketContextTools implements AgentToolProvider {

    public record NoInput() {}

    public record SnapshotInput(String instrument, List<String> instruments) {}

    public record Quote(UUID instrumentId, String symbol, BigDecimal lastPrice, BigDecimal bid, BigDecimal ask, long volume, long oi, Instant ts, boolean stale) {}

    public record Snapshot(Instant asOf, List<Quote> quotes, List<String> noQuote) {}

    public record Regime(boolean available, LocalDate date, Instant asOf, String trend, String volatility, String opening, String breadth,
            String intradayStructure, String eventEnvironment, String marketCondition, List<String> evidence, String classifierVersion, boolean finalLabel) {}

    public record PulseRow(String name, Double value, double contribution, String evidence) {}

    public record SectorRow(String name, String symbol, String label, Double changePct, Double relativePct) {}

    public record Pulse(boolean available, LocalDate date, Instant asOf, String direction, String strength, Integer score, Double coverage,
            List<PulseRow> components, List<String> evidence, String regime, String volatility, String breadth, List<SectorRow> sectors) {}

    public record InstrumentInput(String instrument) {}

    public record Headline(UUID newsId, String title, String url, Instant publishedAt) {}

    public record NewsContext(UUID instrumentId, String symbol, boolean available, double score, String label, int items, List<String> evidence,
            List<Headline> headlines) {}

    public record CalendarInput(String from, String to, String instrument) {}

    public record CalendarEvent(UUID eventId, String type, String scope, String symbol, String title, Instant startsAt, Instant endsAt, boolean allDay,
            double confidence) {}

    public record EventRiskView(boolean available, String level, UUID nextEventId, String nextEvent, Long minutesTo, List<String> evidence) {}

    public record Calendar(boolean enabled, LocalDate from, LocalDate to, List<CalendarEvent> events, EventRiskView risk) {}

    static final JsonNode NO_INPUT = schema("""
            {"type":"object","properties":{},"additionalProperties":false}""");
    static final String INSTRUMENT_PROP = """
            {"type":"string","minLength":1,"description":"Hejje symbol such as NSE:RELIANCE or INDEX:NIFTY 50, or an instrument id"}""";

    private final MarketService market;
    private final RegimeService regime;
    private final PulseService pulse;
    private final NewsService news;
    private final EventService events;
    private final ToolSupport support;
    private final HejjeClock clock;

    MarketContextTools(MarketService market, RegimeService regime, PulseService pulse, NewsService news, EventService events, ToolSupport support,
            HejjeClock clock) {
        this.market = market;
        this.regime = regime;
        this.pulse = pulse;
        this.news = news;
        this.events = events;
        this.support = support;
        this.clock = clock;
    }

    @Override
    public List<AgentTool> tools() {
        return List.of(
                AgentTool.of("get_market_snapshot", "Latest quote (last price, bid/ask, volume, open interest, staleness) for one or up to 20 instruments.",
                        ScopeCatalog.MARKET_READ, schema("""
                                {"type":"object","properties":{"instrument":%s,
                                 "instruments":{"type":"array","items":%s,"minItems":1,"maxItems":20}},"additionalProperties":false}"""
                                .formatted(INSTRUMENT_PROP, INSTRUMENT_PROP)),
                        SnapshotInput.class, Snapshot.class, this::snapshot),
                AgentTool.of("get_market_regime", "Current market regime labels (trend, volatility, opening, breadth, intraday structure, event environment, and the "
                        + "daily market condition: CONFIRMED_UPTREND, UPTREND_UNDER_PRESSURE, RALLY_ATTEMPT or DOWNTREND from distribution and follow-through "
                        + "days) with one evidence sentence per dimension, from the deterministic regime classifier.", ScopeCatalog.MARKET_READ, NO_INPUT,
                        NoInput.class, Regime.class, this::regime),
                AgentTool.of("get_pulse", "Technical Pulse (direction, strength, -100..100 score, per-rule components) and Market Pulse rows (regime, volatility, "
                        + "breadth, sector strength).", ScopeCatalog.MARKET_READ, NO_INPUT, NoInput.class, Pulse.class, this::pulse),
                AgentTool.of("get_news_context", "News bias for an instrument (score -1..1, label, evidence per story) plus the last 24 hours of matched headlines.",
                        ScopeCatalog.MARKET_READ, schema("""
                                {"type":"object","properties":{"instrument":%s},"required":["instrument"],"additionalProperties":false}"""
                                .formatted(INSTRUMENT_PROP)),
                        InstrumentInput.class, NewsContext.class, this::newsContext),
                AgentTool.of("get_event_calendar", "Market and instrument events (holidays, expiries, results, RBI/FOMC/CPI) between two dates (default the next "
                        + "7 days, at most 62), plus the instrument's current event risk when an instrument is given.", ScopeCatalog.MARKET_READ, schema("""
                                {"type":"object","properties":{"from":{"type":"string","format":"date"},"to":{"type":"string","format":"date"},
                                 "instrument":%s},"additionalProperties":false}""".formatted(INSTRUMENT_PROP)),
                        CalendarInput.class, Calendar.class, this::calendar));
    }

    Snapshot snapshot(SnapshotInput in, ToolContext ctx) {
        List<String> refs = new ArrayList<>();
        if (in.instrument() != null) {
            refs.add(in.instrument());
        }
        if (in.instruments() != null) {
            refs.addAll(in.instruments());
        }
        if (refs.isEmpty()) {
            throw ToolException.invalid("Give instrument or instruments");
        }
        List<Quote> quotes = new ArrayList<>();
        List<String> noQuote = new ArrayList<>();
        for (String ref : refs) {
            Instrument i = support.instrument(ref);
            Optional<QuoteSnapshot> q = market.quote(i.id());
            if (q.isEmpty()) {
                noQuote.add(ToolSupport.symbol(i));
                continue;
            }
            QuoteSnapshot s = q.get();
            quotes.add(new Quote(i.id(), ToolSupport.symbol(i), s.lastPrice(), s.bid(), s.ask(), s.volume(), s.oi(), s.ts(), s.stale()));
        }
        return new Snapshot(clock.now(), quotes, noQuote);
    }

    Regime regime(NoInput in, ToolContext ctx) {
        if (!regime.enabled()) {
            return new Regime(false, null, null, null, null, null, null, null, null, null, List.of("The regime engine is disabled"), null, false);
        }
        RegimeSnapshot s = regime.current();
        boolean available = s.trend() != null && s.trend() != Trend.UNKNOWN;
        return new Regime(available, s.date(), s.asOf(), name(s.trend()), name(s.volatility()), name(s.opening()), name(s.breadth()),
                name(s.intradayStructure()), name(s.eventEnvironment()), name(s.marketCondition()), s.evidence(), s.classifierVersion(), s.finalLabel());
    }

    Pulse pulse(NoInput in, ToolContext ctx) {
        if (!pulse.enabled()) {
            return new Pulse(false, null, null, null, null, null, null, List.of(), List.of("Pulse is disabled"), null, null, null, List.of());
        }
        PulseSnapshot p = pulse.current();
        TechnicalPulse t = p.technical();
        MarketPulse m = p.market();
        List<PulseRow> rows = t == null ? List.of() : t.components().stream().map(c -> new PulseRow(c.name(), c.value(), c.contribution(), c.evidence())).toList();
        List<SectorRow> sectors = m == null ? List.of()
                : m.sectors().stream().map(s -> new SectorRow(s.name(), s.symbol(), name(s.label()), s.changePct(), s.relativePct())).toList();
        return new Pulse(t != null && t.coverage() > 0, p.date(), p.asOf(), t == null ? null : name(t.direction()), t == null ? null : name(t.strength()),
                t == null ? null : t.score(), t == null ? null : t.coverage(), rows, t == null ? List.of() : t.evidence(), m == null ? null : m.regime(),
                m == null ? null : m.volatility(), m == null ? null : m.breadth(), sectors);
    }

    NewsContext newsContext(InstrumentInput in, ToolContext ctx) {
        Instrument i = support.instrument(in.instrument());
        NewsBias b = news.bias(i.id());
        List<Headline> headlines = news.items(i.id(), clock.now().minus(Duration.ofHours(24)), 5).stream()
                .map(n -> new Headline(n.id(), n.title(), n.url(), n.publishedAt())).toList();
        return new NewsContext(i.id(), ToolSupport.symbol(i), b.available(), b.score(), b.label() == null ? "NEUTRAL" : b.label().name(), b.items(), b.evidence(), headlines);
    }

    Calendar calendar(CalendarInput in, ToolContext ctx) {
        LocalDate from = ToolSupport.date(in.from(), clock.today());
        LocalDate to = ToolSupport.date(in.to(), from.plusDays(7));
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 62) {
            throw ToolException.invalid("to must be on or after from and at most 62 days later");
        }
        Instrument i = in.instrument() == null ? null : support.instrument(in.instrument());
        List<MarketEvent> list = events.events(from, to, i == null ? null : i.id(), i == null);
        List<CalendarEvent> rows = list.stream().limit(100).map(e -> new CalendarEvent(e.id(), name(e.type()), name(e.scope()), e.symbol(), e.title(),
                e.startsAt(), e.endsAt(), e.allDay(), e.confidence())).toList();
        EventRiskView risk = null;
        if (i != null) {
            EventRisk r = events.risk(i.id());
            MarketEvent next = r.nextEvent();
            risk = new EventRiskView(r.available(), name(r.level()), next == null ? null : next.id(), next == null ? null : events.nextEventLine(r),
                    r.minutesTo(), r.evidence());
        }
        return new Calendar(events.enabled(), from, to, rows, risk);
    }
}
