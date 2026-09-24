package money.hejje.jev;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Price;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.BarMicro;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.pulse.PulseService;
import money.hejje.regime.RegimeProperties;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.risk.RiskService;
import money.hejje.risk.StopSuggester;
import org.springframework.stereotype.Component;

/**
 * Reads what the Jev states are built from (plan M9.5): the session's M1 candles and order-book data per symbol, the
 * volume baseline of previous sessions, the NIFTY index block with regime, Pulse and market condition, and the ATR stop.
 * Every read is capped at the Hejje clock (simulation time in SIM).
 */
@Component
public class JevMarketState {

    private final MarketService market;
    private final InstrumentService instruments;
    private final RegimeService regime;
    private final RegimeProperties regimeProps;
    private final PulseService pulse;
    private final RiskService risk;
    private final HejjeClock clock;
    /** Mean cumulative volume per minute of the session over the previous sessions, per (instrument, date). */
    private final Map<String, double[]> baselines = new ConcurrentHashMap<>();

    JevMarketState(MarketService market, InstrumentService instruments, RegimeService regime, RegimeProperties regimeProps, PulseService pulse,
            RiskService risk, HejjeClock clock) {
        this.market = market;
        this.instruments = instruments;
        this.regime = regime;
        this.regimeProps = regimeProps;
        this.pulse = pulse;
        this.risk = risk;
        this.clock = clock;
    }

    /** One symbol's session so far: its M1 candles (never empty) and features. */
    public record Stock(Instrument instrument, List<Candle> today, JevBotState.Features features) {

        public String symbol() {
            return instrument.hejjeSymbol().format();
        }
    }

    /** The session so far of each symbol that has candles today, keyed by Hejje symbol. */
    public Map<String, Stock> stocks(List<String> symbols) {
        Instant now = clock.now();
        LocalDate day = now.atZone(clock.zone()).toLocalDate();
        Instant open = clock.sessionWindow(day).open().toInstant();
        Map<String, Stock> out = new LinkedHashMap<>();
        for (String s : symbols) {
            instruments.resolve(s).ifPresent(i -> {
                List<Candle> today = market.candles(i.id(), Timeframe.M1, open, now);
                if (!today.isEmpty()) {
                    List<BarMicro> micro = market.micro(i.id(), Timeframe.M1, open, now);
                    out.put(i.hejjeSymbol().format(), new Stock(i, today, JevBotState.features(today, baseline(i.id(), day, open), micro)));
                }
            });
        }
        return out;
    }

    /** The index block; breadth above VWAP over {@code stocks}. */
    public ObjectNode index(Map<String, Stock> stocks) {
        Instant now = clock.now();
        Instant open = clock.sessionWindow(now.atZone(clock.zone()).toLocalDate()).open().toInstant();
        List<Candle> nifty = instruments.resolve(regimeProps.indexSymbol()).map(i -> market.candles(i.id(), Timeframe.M1, open, now)).orElse(List.of());
        long above = stocks.values().stream().filter(s -> s.features().aboveVwap()).count();
        Double share = stocks.isEmpty() ? null : (double) above / stocks.size();
        RegimeSnapshot r = safe(regime::current);
        var p = safe(pulse::current);
        return JevBotState.index(nifty, share, r == null ? null : r.trend().name(), r == null ? null : r.volatility().name(),
                p == null || p.technical() == null ? null : p.technical().direction().name(), r == null ? null : r.marketCondition().name());
    }

    /** {@link StopSuggester} with ATR(14) of {@code timeframe} bars of the last few sessions, within the mode's max stop distance. */
    public BigDecimal stop(Side side, Instrument instrument, double price, Timeframe timeframe, ExecutionMode mode) {
        Instant now = clock.now();
        List<Candle> bars = market.candles(instrument.id(), timeframe, now.minus(Duration.ofDays(7)), now);
        Double a = JevBotState.atr(bars, StopSuggester.ATR_PERIOD);
        OptionalDouble atr = a == null ? OptionalDouble.empty() : OptionalDouble.of(a);
        BigDecimal maxPct = risk.limits(mode).maxStopDistancePct();
        BigDecimal tick = instrument.tickSize() == null ? new BigDecimal("0.05") : instrument.tickSize();
        return StopSuggester.suggest(side, Price.of(BigDecimal.valueOf(price).setScale(2, java.math.RoundingMode.HALF_UP)), atr, maxPct, tick).stop().value();
    }

    /** Mean cumulative volume by minute of the session over the previous five sessions with M1 data; cached per day. */
    private double[] baseline(UUID id, LocalDate day, Instant open) {
        return baselines.computeIfAbsent(id + "/" + day, k -> {
            List<Candle> history = market.candles(id, Timeframe.M1, open.minus(Duration.ofDays(10)), open.minusSeconds(1));
            Map<LocalDate, double[]> byDay = new LinkedHashMap<>();
            for (Candle c : history) {
                LocalDate d = c.openTime().atZone(clock.zone()).toLocalDate();
                int minute = (int) Duration.between(clock.sessionWindow(d).open().toInstant(), c.openTime()).toMinutes();
                if (minute < 0 || minute >= 375) {
                    continue;
                }
                byDay.computeIfAbsent(d, x -> new double[375])[minute] += c.volume();
            }
            List<double[]> days = new ArrayList<>(byDay.values());
            if (days.isEmpty()) {
                return null;
            }
            days = days.subList(Math.max(0, days.size() - 5), days.size());
            double[] mean = new double[375];
            for (double[] v : days) {
                double cumulative = 0;
                for (int m = 0; m < 375; m++) {
                    cumulative += v[m];
                    mean[m] += cumulative / days.size();
                }
            }
            return mean;
        });
    }

    private static <T> T safe(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
